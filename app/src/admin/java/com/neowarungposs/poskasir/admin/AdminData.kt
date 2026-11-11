package com.neowarungposs.poskasir.admin

import com.neowarungposs.poskasir.core.AppJson
import com.neowarungposs.poskasir.core.Plan
import com.neowarungposs.poskasir.core.SupabaseClient
import com.neowarungposs.poskasir.core.SystemSettings
import com.neowarungposs.poskasir.core.Tenant
import com.neowarungposs.poskasir.core.parseInstant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

@Serializable
data class TenantRef(
    val businessName: String,
    val customerCode: String,
    val payCode: Int,
    val phoneWa: String? = null,
)

@Serializable
data class Claim(
    val id: String,
    val tenantId: String,
    val planId: String? = null,
    val planPriceSnapshot: Long? = null,
    val expectedAmount: Long? = null,
    val senderName: String? = null,
    val proofPath: String? = null,
    val transferAt: String? = null,
    val status: String,
    val rejectionReason: String? = null,
    val createdAt: String,
    val tenants: TenantRef? = null,
)

@Serializable
data class Incoming(
    val id: String,
    val channel: String,
    val amount: Long,
    val receivedAt: String,
    val senderName: String? = null,
    val bankRef: String? = null,
    val decodedTenantId: String? = null,
    val status: String,
    val note: String? = null,
)

@Serializable
data class DecodeRow(
    val tenantId: String? = null,
    val customerCode: String? = null,
    val businessName: String? = null,
    val lifecycle: String? = null,
    val payCode: Int,
    val baseAmount: Long,
    val planId: String? = null,
    val planMonths: Int? = null,
    val planPrice: Long? = null,
    val planIsActive: Boolean? = null,
    val matchKind: String,
)

@Serializable
data class QueueItem(
    val id: String,
    val tenantId: String,
    val kind: String,
    val periodEnd: String,
    val planSuggestionId: String? = null,
    val amount: Long? = null,
    val status: String,
    val tenants: TenantRef? = null,
)

@Serializable
data class ReminderSettings(
    val enabled: Boolean = true,
    val daysBefore: List<Int> = listOf(7, 3, 1),
    val remindOnGrace: Boolean = true,
    val runHour: Int = 9,
    val waTemplate: String = "",
    val autoChurnAfterDays: Int = 30,
)

/** Thin wrapper over admin RPCs and admin-readable tables (RLS: SUPER_ADMIN only). */
class AdminRepo(private val c: SupabaseClient) {

    suspend fun claims(status: String): List<Claim> = AppJson.decodeFromString(
        c.get("payment_confirmations?select=*,tenants(business_name,customer_code,pay_code,phone_wa)&status=eq.$status&order=created_at.asc&limit=200")
    )

    suspend fun claimsForTenant(tenantId: String): List<Claim> = AppJson.decodeFromString(
        c.get("payment_confirmations?select=*&tenant_id=eq.$tenantId&order=created_at.desc&limit=50")
    )

    suspend fun incoming(status: String = "UNMATCHED"): List<Incoming> = AppJson.decodeFromString(
        c.get("incoming_payments?select=*&status=eq.$status&order=received_at.desc&limit=200")
    )

    suspend fun tenants(lifecycle: String): List<Tenant> = AppJson.decodeFromString(
        c.get("tenants?select=*&lifecycle=eq.$lifecycle&order=created_at.desc&limit=500")
    )

    suspend fun tenant(id: String): Tenant? =
        AppJson.decodeFromString<List<Tenant>>(c.get("tenants?select=*&id=eq.$id")).firstOrNull()

    suspend fun tenantByCode(code: String): Tenant? =
        AppJson.decodeFromString<List<Tenant>>(c.get("tenants?select=*&customer_code=eq.$code")).firstOrNull()

    suspend fun plans(): List<Plan> = AppJson.decodeFromString(c.get("plans?select=*&is_active=eq.true&order=months.asc"))

    suspend fun settings(): SystemSettings =
        AppJson.decodeFromString<List<SystemSettings>>(c.get("system_settings?select=*")).first()

    suspend fun reminderSettings(): ReminderSettings =
        AppJson.decodeFromString<List<ReminderSettings>>(c.get("reminder_settings?select=*")).first()

    suspend fun queue(): List<QueueItem> = AppJson.decodeFromString(
        c.get("wa_reminder_queue?select=*,tenants(business_name,customer_code,pay_code,phone_wa)&status=eq.PENDING&order=period_end.asc&limit=200")
    )

    suspend fun signedProof(path: String): String? = c.signedUrl("payment-proofs", path)

    suspend fun decode(amount: Long): DecodeRow? = AppJson.decodeFromString<List<DecodeRow>>(
        c.rpc("decode_amount", buildJsonObject { put("p_amount", amount) })
    ).firstOrNull()

    suspend fun recordIncoming(channel: String, amount: Long, receivedAt: Instant, sender: String?, ref: String?, note: String?): String =
        AppJson.decodeFromString<String>(
            c.rpc("record_incoming_payment", buildJsonObject {
                put("p_channel", channel)
                put("p_amount", amount)
                put("p_received_at", receivedAt.toString())
                put("p_sender_name", sender)
                put("p_bank_ref", ref)
                put("p_note", note)
            })
        )

    suspend fun approve(incomingId: String, tenantId: String, planId: String?, months: Int?, claimId: String?, note: String?): Instant? {
        val raw = c.rpc("approve_payment", buildJsonObject {
            put("p_incoming_id", incomingId)
            put("p_tenant_id", tenantId)
            put("p_plan_id", planId)
            put("p_months", months)
            put("p_claim_id", claimId)
            put("p_note", note)
        })
        return parseInstant(AppJson.decodeFromString<String>(raw))
    }

    suspend fun reject(claimId: String, reason: String) {
        c.rpc("reject_payment", buildJsonObject { put("p_claim_id", claimId); put("p_reason", reason) })
    }

    suspend fun markChurned(tenantId: String, reason: String) {
        c.rpc("mark_churned", buildJsonObject { put("p_tenant_id", tenantId); put("p_reason", reason) })
    }

    suspend fun upsertPlan(months: Int, discount: Double, badge: String?, promoEndsAt: Instant?, active: Boolean) {
        c.rpc("upsert_plan", buildJsonObject {
            put("p_months", months)
            put("p_discount_pct", discount)
            put("p_badge", badge)
            put("p_promo_ends_at", promoEndsAt?.toString())
            put("p_is_active", active)
        })
    }

    suspend fun setQueueStatus(id: String, status: String) {
        val userId = c.session.state.value?.userId
        c.patch("wa_reminder_queue?id=eq.$id", buildJsonObject {
            put("status", status)
            put("sent_at", Instant.now().toString())
            put("sent_by", userId)
        }.toString())
    }

    suspend fun saveSettings(body: Map<String, JsonPrimitive>) {
        c.patch("system_settings?id=eq.1", buildJsonObject { body.forEach { (k, v) -> put(k, v) } }.toString())
    }

    suspend fun saveReminderSettings(s: ReminderSettings) {
        c.patch("reminder_settings?id=eq.1", AppJson.encodeToString(ReminderSettings.serializer(), s))
    }

    suspend fun count(table: String, filter: String): Int =
        AppJson.decodeFromString<List<kotlinx.serialization.json.JsonObject>>(c.get("$table?select=id&$filter&limit=1000")).size
}

/** access_until = greatest(trial_ends_at, paid_until). */
fun Tenant.accessUntil(): Instant? {
    val trial = parseInstant(trialEndsAt)
    val paid = parseInstant(paidUntil)
    return listOfNotNull(trial, paid).maxOrNull()
}
