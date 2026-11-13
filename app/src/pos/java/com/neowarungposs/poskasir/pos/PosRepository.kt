package com.neowarungposs.poskasir.pos

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.neowarungposs.poskasir.App
import com.neowarungposs.poskasir.AppContainer
import com.neowarungposs.poskasir.core.AppJson
import com.neowarungposs.poskasir.core.Entitlement
import com.neowarungposs.poskasir.core.Outlet
import com.neowarungposs.poskasir.core.ProductOutletRow
import com.neowarungposs.poskasir.core.TenantInfo
import com.neowarungposs.poskasir.ui.AppTheme
import com.neowarungposs.poskasir.ui.ThemeState
import kotlinx.serialization.builtins.ListSerializer
import com.neowarungposs.poskasir.core.ProductDto
import com.neowarungposs.poskasir.core.parseInstant
import com.neowarungposs.poskasir.core.str
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.Request
import java.io.File
import java.net.URLEncoder
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Locally cached entitlement + trusted time anchors (Section 7 of master prompt). */
@Serializable
data class EntState(
    val serverNowMs: Long,
    val elapsedAtSync: Long,
    val lastTrustedMs: Long,
    val accessUntilMs: Long,
    val graceUntilMs: Long,
    val status: String,
    val customerCode: String,
    val payCode: Int,
    val maxOfflineDays: Int,
)

@Serializable
data class CashierRow(val userId: String, val outletId: String? = null, val email: String? = null)

@Serializable
data class VoidOrderRef(val invoiceNo: String, val total: Long, val outletId: String, val createdAt: String)

@Serializable
data class VoidRow(
    val id: String,
    val orderId: String,
    val reason: String,
    val status: String,
    val createdAt: String,
    val salesOrders: VoidOrderRef? = null,
)

@Serializable
data class ServerOrder(
    val id: String,
    val invoiceNo: String,
    val createdAt: String,
    val total: Long,
    val subtotal: Long? = null,
    val discount: Long = 0,
    val promoSummary: String? = null,
    val cashReceived: Long? = null,
    val paymentMethod: String,
    val status: String,
    val outletId: String,
    val orderType: String = "DINE_IN",
    val tableNo: String? = null,
) {
    fun toEntity() = OrderEntity(
        id, outletId, invoiceNo, paymentMethod, total, cashReceived,
        parseInstant(createdAt)?.toEpochMilli() ?: 0L, synced = true, status = status, orderType = orderType,
        tableNo = tableNo, subtotal = subtotal ?: total, discount = discount, promoSummary = promoSummary,
    )
}

@Serializable
data class ServerItem(
    val id: String,
    val orderId: String,
    val productId: String? = null,
    val productName: String,
    val unitPrice: Long,
    val qty: Double,
    val subtotal: Long,
    val unitCost: Long = 0,
    val note: String? = null,
)

@Serializable
data class SaleRow(val outletId: String, val total: Long, val status: String)

enum class GateState { OK, WARNING, GRACE, EXPIRED, NEED_ONLINE, UNKNOWN }

data class Gate(val state: GateState, val daysLeft: Long, val canSell: Boolean)

data class CartLine(val product: ProductEntity, val qty: Int, val unitPrice: Long = product.price, val note: String? = null)

object PosModule {
    @Volatile
    private var repo: PosRepository? = null

    fun repo(context: Context): PosRepository = repo ?: synchronized(this) {
        repo ?: PosRepository((context.applicationContext as App).container).also { repo = it }
    }
}

class PosRepository(val container: AppContainer) {
    private val client = container.supabase
    val dao: PosDao = PosDatabase.get(container.appContext).dao()
    private val syncMutex = Mutex()

    private val _ent = MutableStateFlow<EntState?>(null)
    val ent: StateFlow<EntState?> = _ent

    private val _outlet = MutableStateFlow<Outlet?>(null)
    /** Outlet this device sells for (cashier: own outlet; owner: chosen outlet). */
    val outlet: StateFlow<Outlet?> = _outlet
    private val _promos = MutableStateFlow<List<PromoDto>>(emptyList())
    val promos: StateFlow<List<PromoDto>> = _promos
    private val _productOutlets = MutableStateFlow<List<ProductOutletRow>>(emptyList())
    val productOutlets: StateFlow<List<ProductOutletRow>> = _productOutlets
    private val _tenant = MutableStateFlow<TenantInfo?>(null)
    val tenant: StateFlow<TenantInfo?> = _tenant

    /** Loads cached tenant data so the POS works offline right after start. */
    suspend fun loadCaches() {
        if (_outlet.value == null) _outlet.value = currentOutlet()
        if (_promos.value.isEmpty()) _promos.value = dao.kv("promos")?.let { runCatching { AppJson.decodeFromString<List<PromoDto>>(it) }.getOrNull() } ?: emptyList()
        if (_productOutlets.value.isEmpty()) _productOutlets.value = dao.kv("product_outlets")?.let { runCatching { AppJson.decodeFromString<List<ProductOutletRow>>(it) }.getOrNull() } ?: emptyList()
        if (_tenant.value == null) _tenant.value = dao.kv("tenant")?.let { runCatching { AppJson.decodeFromString<TenantInfo>(it) }.getOrNull() }
    }

    /** Effective price / availability of a product in the current outlet (D23). */
    fun effective(p: ProductEntity, outletId: String?): Pair<Boolean, Long> {
        val row = _productOutlets.value.firstOrNull { it.productId == p.id && it.outletId == outletId }
        val outletOk = p.outletId == null || p.outletId == outletId
        return ((row?.isAvailable ?: true) && outletOk && p.isActive) to (row?.priceOverride ?: p.price)
    }

    private val deviceId: String by lazy {
        Settings.Secure.getString(container.appContext.contentResolver, Settings.Secure.ANDROID_ID) ?: "device"
    }

    // ---------------------------------------------------------------- entitlement
    suspend fun loadEntitlement() {
        if (_ent.value == null) {
            _ent.value = container.secureStore.get(ENT_KEY)?.let {
                runCatching { AppJson.decodeFromString<EntState>(it) }.getOrNull()
            }
        }
    }

    suspend fun refreshEntitlement(): EntState {
        val raw = client.rpc("get_entitlement")
        val e = AppJson.decodeFromString<List<Entitlement>>(raw).first()
        val serverNow = parseInstant(e.serverNow)?.toEpochMilli() ?: System.currentTimeMillis()
        val state = EntState(
            serverNowMs = serverNow,
            elapsedAtSync = SystemClock.elapsedRealtime(),
            lastTrustedMs = serverNow,
            accessUntilMs = parseInstant(e.accessUntil)?.toEpochMilli() ?: 0,
            graceUntilMs = parseInstant(e.graceUntil)?.toEpochMilli() ?: 0,
            status = e.status,
            customerCode = e.customerCode,
            payCode = e.payCode,
            maxOfflineDays = e.maxOfflineDays,
        )
        saveEnt(state)
        return state
    }

    private suspend fun saveEnt(state: EntState) {
        container.secureStore.put(ENT_KEY, AppJson.encodeToString(EntState.serializer(), state))
        _ent.value = state
    }

    /** Clock that cannot be moved backwards by changing the phone time. */
    fun trustedNow(s: EntState): Long {
        val elapsed = SystemClock.elapsedRealtime()
        val t = if (elapsed >= s.elapsedAtSync) s.serverNowMs + (elapsed - s.elapsedAtSync)
        else maxOf(System.currentTimeMillis(), s.lastTrustedMs) // device rebooted
        return maxOf(t, s.lastTrustedMs)
    }

    suspend fun persistTrustedTime() {
        val s = _ent.value ?: return
        val now = trustedNow(s)
        if (now > s.lastTrustedMs) saveEnt(s.copy(lastTrustedMs = now))
    }

    fun gate(): Gate {
        val s = _ent.value ?: return Gate(GateState.UNKNOWN, 0, false)
        val now = trustedNow(s)
        val daysLeft = Math.floorDiv(s.accessUntilMs - now, DAY_MS)
        val offlineTooLong = now - s.serverNowMs > s.maxOfflineDays * DAY_MS
        return when {
            now > s.graceUntilMs -> Gate(GateState.EXPIRED, daysLeft, false)
            offlineTooLong -> Gate(GateState.NEED_ONLINE, daysLeft, false)
            now > s.accessUntilMs -> Gate(GateState.GRACE, daysLeft, true)
            daysLeft < 7 -> Gate(GateState.WARNING, daysLeft, true)
            else -> Gate(GateState.OK, daysLeft, true)
        }
    }

    // ---------------------------------------------------------------- tenant info
    suspend fun businessName(): String = dao.kv("business_name") ?: ""

    suspend fun currentOutlet(): Outlet? = dao.kv("outlet")?.let { runCatching { AppJson.decodeFromString<Outlet>(it) }.getOrNull() }

    suspend fun cachedOutlets(): List<Outlet> =
        dao.kv("outlets_all")?.let { runCatching { AppJson.decodeFromString<List<Outlet>>(it) }.getOrNull() } ?: listOfNotNull(currentOutlet())

    suspend fun refreshTenantInfo() {
        val claims = client.session.claims() ?: return
        val info = AppJson.decodeFromString<List<TenantInfo>>(
            client.get("tenants?select=business_name,phone_wa,wa_opt_in,email,logo_path,login_bg_path,theme&limit=1")
        ).firstOrNull()
        if (info != null) {
            dao.putKv(KvEntity("business_name", info.businessName))
            dao.putKv(KvEntity("tenant", AppJson.encodeToString(TenantInfo.serializer(), info)))
            _tenant.value = info
            runCatching { container.branding.sync(client, info.logoPath, info.loginBgPath) }
            AppTheme.entries.firstOrNull { it.name == info.theme }?.let { ThemeState.set(container.appContext, it) }
        }

        val outlets = if (claims.appRole == "BRANCH_CASHIER" && claims.outletId != null)
            AppJson.decodeFromString<List<Outlet>>(client.get("outlets?select=*&id=eq.${claims.outletId}"))
        else AppJson.decodeFromString<List<Outlet>>(client.get("outlets?select=*&deleted_at=is.null&order=created_at.asc"))
        dao.putKv(KvEntity("outlets_all", AppJson.encodeToString(ListSerializer(Outlet.serializer()), outlets)))
        val activeId = dao.kv("active_outlet_id")
        val chosen = outlets.firstOrNull { it.id == activeId } ?: outlets.firstOrNull { it.isMain } ?: outlets.firstOrNull()
        if (chosen != null) {
            dao.putKv(KvEntity("outlet", AppJson.encodeToString(Outlet.serializer(), chosen)))
            _outlet.value = chosen
        }
    }

    /** Owner only: switch the outlet this device sells for. */
    suspend fun setActiveOutlet(o: Outlet) {
        dao.putKv(KvEntity("active_outlet_id", o.id))
        dao.putKv(KvEntity("outlet", AppJson.encodeToString(Outlet.serializer(), o)))
        _outlet.value = o
    }

    suspend fun pullPromos() {
        val list = AppJson.decodeFromString<List<PromoDto>>(client.get("promotions?select=*&deleted_at=is.null&order=created_at.desc"))
        dao.putKv(KvEntity("promos", AppJson.encodeToString(ListSerializer(PromoDto.serializer()), list)))
        _promos.value = list
    }

    suspend fun pullProductOutlets() {
        val list = AppJson.decodeFromString<List<ProductOutletRow>>(client.get("product_outlets?select=product_id,outlet_id,is_available,price_override&limit=10000"))
        dao.putKv(KvEntity("product_outlets", AppJson.encodeToString(ListSerializer(ProductOutletRow.serializer()), list)))
        _productOutlets.value = list
    }

    // ---------------------------------------------------------------- products
    suspend fun pullProducts() {
        val since = dao.kv("products_since")
        val filter = since?.let { "&updated_at=gt." + URLEncoder.encode(it, "UTF-8") } ?: ""
        val raw = client.get("products?select=*&order=updated_at.asc&limit=1000$filter")
        val list = AppJson.decodeFromString<List<ProductDto>>(raw)
        if (list.isEmpty()) return
        dao.upsertProducts(list.map {
            ProductEntity(it.id, it.outletId, it.name, it.category, it.price, it.isActive, it.deletedAt != null, it.updatedAt, it.imagePath, it.costPrice)
        })
        list.last().updatedAt?.let { dao.putKv(KvEntity("products_since", it)) }
        list.filter { it.imagePath != null && it.deletedAt == null }.forEach { p ->
            runCatching { downloadImage(p.id, p.imagePath!!, p.updatedAt ?: "") }
        }
    }

    /** Product photos are cached on the device so the POS shows them offline. */
    fun localImage(productId: String): File = File(container.appContext.filesDir, "products/$productId.webp")

    private suspend fun downloadImage(productId: String, path: String, version: String) {
        val file = localImage(productId)
        if (file.exists() && dao.kv("img_$productId") == version) return
        val url = client.signedUrl("outlet-assets", path) ?: return
        val bytes = withContext(Dispatchers.IO) {
            client.http.newCall(Request.Builder().url(url).build()).execute().use { if (it.isSuccessful) it.body?.bytes() else null }
        } ?: return
        withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
        }
        dao.putKv(KvEntity("img_$productId", version))
    }

    suspend fun saveProduct(
        id: String?, name: String, category: String?, price: Long, active: Boolean, photo: ByteArray? = null,
        costPrice: Long = 0, perOutlet: List<ProductOutletRow> = emptyList(),
    ) {
        val productId = id ?: uuidV7()
        val tenantId = client.session.claims()?.tenantId
        val imagePath = if (photo != null && tenantId != null) {
            val path = "$tenantId/products/$productId-${System.currentTimeMillis()}.webp"
            client.upload("outlet-assets", path, photo, "image/webp")
            path
        } else null
        val body = buildJsonObject {
            if (imagePath != null) put("image_path", imagePath)
            put("cost_price", costPrice)
            put("name", name.trim())
            put("category", category?.trim()?.ifEmpty { null })
            put("price", price)
            put("is_active", active)
        }
        if (id == null) {
            val withTenant = buildJsonObject {
                body.forEach { (k, v) -> put(k, v) }
                put("id", productId)
                put("tenant_id", tenantId) // overwritten server-side from JWT anyway
            }
            client.post("products", withTenant.toString())
        } else {
            client.patch("products?id=eq.$id", body.toString())
        }
        if (perOutlet.isNotEmpty()) {
            val rows = buildJsonArray {
                perOutlet.forEach { r ->
                    add(buildJsonObject {
                        put("product_id", productId); put("outlet_id", r.outletId); put("tenant_id", tenantId)
                        put("is_available", r.isAvailable); put("price_override", r.priceOverride)
                    })
                }
            }
            client.post("product_outlets?on_conflict=product_id,outlet_id", rows.toString(), prefer = "resolution=merge-duplicates,return=minimal")
            pullProductOutlets()
        }
        pullProducts()
    }

    suspend fun deleteProduct(id: String) {
        client.patch("products?id=eq.$id", buildJsonObject { put("deleted_at", Instant.now().toString()) }.toString())
        pullProducts()
    }

    // ---------------------------------------------------------------- orders
    suspend fun checkout(
        lines: List<CartLine>, method: String, cashReceived: Long?,
        applied: List<AppliedPromo> = emptyList(), orderType: String = "DINE_IN", tableNo: String? = null,
    ): Pair<OrderEntity, List<OrderItemEntity>> {
        val outlet = currentOutlet() ?: throw IllegalStateException("Data outlet belum tersedia. Hubungkan internet sekali.")
        val s = _ent.value
        val createdAt = s?.let { trustedNow(it) } ?: System.currentTimeMillis()
        val orderId = uuidV7()
        val items = lines.map {
            OrderItemEntity(uuidV7(), orderId, it.product.id, it.product.name, it.unitPrice, it.qty.toDouble(),
                it.unitPrice * it.qty, unitCost = it.product.costPrice, note = it.note)
        }
        val subtotal = items.sumOf { it.subtotal }
        val discount = applied.sumOf { it.discount }.coerceAtMost(subtotal)
        val order = OrderEntity(
            id = orderId,
            outletId = outlet.id,
            invoiceNo = nextInvoiceNo(outlet.code, createdAt),
            paymentMethod = method,
            total = subtotal - discount,
            cashReceived = cashReceived,
            createdAtMs = createdAt,
            synced = false,
            orderType = orderType,
            tableNo = tableNo?.ifBlank { null },
            subtotal = subtotal,
            discount = discount,
            promoSummary = applied.joinToString("; ") { "${it.name} -${it.discount}" }.ifEmpty { null },
        )
        dao.saveOrder(order, items)
        persistTrustedTime()
        return order to items
    }

    private suspend fun nextInvoiceNo(outletCode: String, atMs: Long): String {
        val day = Instant.ofEpochMilli(atMs).atZone(ZoneId.systemDefault()).toLocalDate()
            .format(DateTimeFormatter.ofPattern("yyMMdd"))
        val key = "seq_$day"
        val seq = (dao.kv(key)?.toIntOrNull() ?: 0) + 1
        dao.putKv(KvEntity(key, seq.toString()))
        val dev = deviceId.takeLast(4).uppercase()
        return "$outletCode-$dev-$day-${seq.toString().padStart(4, '0')}"
    }

    suspend fun pushOrders(): Int {
        var total = 0
        while (true) {
            val batch = dao.pendingOrders(100)
            if (batch.isEmpty()) break
            val items = dao.itemsFor(batch.map { it.id }).groupBy { it.orderId }
            val payload = buildJsonArray {
                batch.forEach { o ->
                    add(buildJsonObject {
                        put("id", o.id)
                        put("outlet_id", o.outletId)
                        put("device_id", deviceId)
                        put("invoice_no", o.invoiceNo)
                        put("payment_method", o.paymentMethod)
                        put("total", o.total)
                        put("created_at", Instant.ofEpochMilli(o.createdAtMs).toString())
                        put("order_type", o.orderType)
                        put("table_no", o.tableNo)
                        put("subtotal", if (o.subtotal > 0) o.subtotal else o.total)
                        put("discount", o.discount)
                        put("promo_summary", o.promoSummary)
                        put("items", buildJsonArray {
                            items[o.id].orEmpty().forEach { i ->
                                add(buildJsonObject {
                                    put("id", i.id)
                                    put("product_id", i.productId)
                                    put("product_name", i.productName)
                                    put("unit_price", i.unitPrice)
                                    put("qty", i.qty)
                                    put("subtotal", i.subtotal)
                                    put("unit_cost", i.unitCost)
                                    put("note", i.note)
                                })
                            }
                        })
                    })
                }
            }
            client.rpc("sync_push_orders", buildJsonObject { put("p_orders", payload) })
            dao.markSynced(batch.map { it.id })
            total += batch.size
        }
        return total
    }

    /** Full sync: push sales first (never lose them), then pull master data and entitlement. */
    suspend fun syncAll() = syncMutex.withLock {
        if (client.session.state.value == null || client.session.claims()?.tenantId == null) return@withLock
        pushOrders()
        refreshTenantInfo()
        pullProducts()
        runCatching { pullProductOutlets() }
        runCatching { pullPromos() }
        pullVoids()
        refreshEntitlement()
    }

    /** Applies voids approved on the server to local history. */
    suspend fun pullVoids() {
        val since = dao.kv("voids_since") ?: "1970-01-01T00:00:00Z"
        val raw = client.get("sales_orders?select=id,updated_at&status=eq.VOIDED&updated_at=gt." +
            URLEncoder.encode(since, "UTF-8") + "&order=updated_at.asc&limit=1000")
        val rows = AppJson.parseToJsonElement(raw) as JsonArray
        if (rows.isEmpty()) return
        dao.setStatus(rows.mapNotNull { it.str("id") }, "VOIDED")
        rows.last().str("updated_at")?.let { dao.putKv(KvEntity("voids_since", it)) }
    }

    // ---------------------------------------------------------------- voids
    suspend fun requestVoid(order: OrderEntity, reason: String) {
        if (!order.synced) pushOrders()
        client.rpc("request_void", buildJsonObject { put("p_order_id", order.id); put("p_reason", reason) })
    }

    suspend fun pendingVoids(): List<VoidRow> = AppJson.decodeFromString(
        client.get("void_requests?select=*,sales_orders(invoice_no,total,outlet_id,created_at)&status=eq.PENDING&order=created_at.asc")
    )

    suspend fun decideVoid(id: String, approve: Boolean) {
        client.rpc("decide_void", buildJsonObject { put("p_void_id", id); put("p_approve", approve) })
        pullVoids()
    }

    // ---------------------------------------------------------------- outlets & cashiers (TENANT_ADMIN)
    suspend fun outlets(): List<Outlet> =
        AppJson.decodeFromString(client.get("outlets?select=*&deleted_at=is.null&order=created_at.asc"))

    suspend fun saveOutlet(id: String?, code: String, name: String, address: String?) {
        val body = buildJsonObject {
            put("code", code.trim().uppercase())
            put("name", name.trim())
            put("address", address?.trim()?.ifEmpty { null })
        }
        if (id == null) {
            client.post("outlets", buildJsonObject {
                body.forEach { (k, v) -> put(k, v) }
                put("tenant_id", client.session.claims()?.tenantId)
            }.toString())
        } else client.patch("outlets?id=eq.$id", body.toString())
    }

    suspend fun closeOutlet(id: String) {
        client.patch("outlets?id=eq.$id", buildJsonObject { put("deleted_at", Instant.now().toString()) }.toString())
    }

    suspend fun cashiers(): List<CashierRow> =
        AppJson.decodeFromString(client.get("memberships?select=user_id,outlet_id,email&role=eq.BRANCH_CASHIER"))

    suspend fun manageCashier(body: JsonObject) {
        client.function("manage_cashier", body)
    }

    // ---------------------------------------------------------------- reports
    suspend fun salesSince(fromMs: Long): List<SaleRow> = AppJson.decodeFromString(
        client.get("sales_orders?select=outlet_id,total,status&created_at=gte." +
            URLEncoder.encode(Instant.ofEpochMilli(fromMs).toString(), "UTF-8") + "&limit=20000")
    )

    // ---------------------------------------------------------------- promotions (TENANT_ADMIN)
    suspend fun savePromo(id: String?, body: JsonObject) {
        if (id == null) client.post("promotions", buildJsonObject {
            body.forEach { (k, v) -> put(k, v) }
            put("tenant_id", client.session.claims()?.tenantId)
        }.toString())
        else client.patch("promotions?id=eq.$id", body.toString())
        pullPromos()
    }

    suspend fun deletePromo(id: String) {
        client.patch("promotions?id=eq.$id", buildJsonObject { put("deleted_at", Instant.now().toString()) }.toString())
        pullPromos()
    }

    // ---------------------------------------------------------------- receipt, branding, contact
    suspend fun saveReceiptSettings(outletId: String, header: String?, footer: String?, phone: String?, showLogo: Boolean) {
        client.patch("outlets?id=eq.$outletId", buildJsonObject {
            put("receipt_header", header?.trim()?.ifEmpty { null })
            put("receipt_footer", footer?.trim()?.ifEmpty { null })
            put("phone", phone?.trim()?.ifEmpty { null })
            put("receipt_show_logo", showLogo)
        }.toString())
        refreshTenantInfo()
    }

    suspend fun updateBranding(logo: ByteArray?, background: ByteArray?, theme: AppTheme?, clearLogo: Boolean = false, clearBg: Boolean = false) {
        val tenantId = client.session.claims()?.tenantId ?: return
        val ts = System.currentTimeMillis()
        val logoPath = logo?.let { "$tenantId/branding/logo-$ts.webp".also { p -> client.upload("outlet-assets", p, it, "image/webp") } }
        val bgPath = background?.let { "$tenantId/branding/login-$ts.webp".also { p -> client.upload("outlet-assets", p, it, "image/webp") } }
        client.rpc("update_branding", buildJsonObject {
            put("p_logo_path", logoPath); put("p_login_bg_path", bgPath); put("p_theme", theme?.name)
            put("p_clear_logo", clearLogo); put("p_clear_bg", clearBg)
        })
        refreshTenantInfo()
    }


    /** True when the platform charges for extra outlets (D27). The amount is never shown in the app (D1). */
    suspend fun outletSurchargeActive(): Boolean =
        (AppJson.parseToJsonElement(client.get("system_settings?select=extra_outlet_monthly_price&id=eq.1")) as JsonArray)
            .firstOrNull()?.str("extra_outlet_monthly_price")?.toLongOrNull()?.let { it > 0 } ?: false

    // ---------------------------------------------------------------- reports & server history (TENANT_ADMIN)
    suspend fun report(fromMs: Long, toMs: Long, outletId: String?): JsonObject =
        AppJson.parseToJsonElement(client.rpc("report_summary", buildJsonObject {
            put("p_from", Instant.ofEpochMilli(fromMs).toString())
            put("p_to", Instant.ofEpochMilli(toMs).toString())
            put("p_outlet", outletId)
        })) as JsonObject

    suspend fun serverOrders(outletId: String?, fromMs: Long): List<ServerOrder> {
        val outletFilter = outletId?.let { "&outlet_id=eq.$it" } ?: ""
        return AppJson.decodeFromString(client.get("sales_orders?select=id,invoice_no,created_at,total,subtotal,discount,promo_summary,payment_method,status,outlet_id,order_type,table_no" +
            "&created_at=gte." + URLEncoder.encode(Instant.ofEpochMilli(fromMs).toString(), "UTF-8") + outletFilter + "&order=created_at.desc&limit=500"))
    }

    suspend fun serverOrderItems(orderId: String): List<OrderItemEntity> =
        AppJson.decodeFromString<List<ServerItem>>(client.get("sales_order_items?select=id,order_id,product_id,product_name,unit_price,qty,subtotal,unit_cost,note&order_id=eq.$orderId"))
            .map { OrderItemEntity(it.id, it.orderId, it.productId, it.productName, it.unitPrice, it.qty, it.subtotal, it.unitCost, it.note) }

    // ---------------------------------------------------------------- printer
    suspend fun printerAddress(): String? = dao.kv("printer_address")
    suspend fun printerName(): String? = dao.kv("printer_name")
    suspend fun paperWidth(): Int = dao.kv("paper_width")?.toIntOrNull() ?: 32
    suspend fun setPrinter(p: PairedPrinter) {
        dao.putKv(KvEntity("printer_address", p.address)); dao.putKv(KvEntity("printer_name", p.name))
    }
    suspend fun setPaperWidth(chars: Int) = dao.putKv(KvEntity("paper_width", chars.toString()))

    suspend fun printReceipt(order: OrderEntity, items: List<OrderItemEntity>) {
        val address = printerAddress() ?: throw IllegalStateException("Pilih printer dulu di Akun > Printer.")
        val data = ReceiptBuilder.build(businessName(), currentOutlet(), order, items, paperWidth(), container.branding.logo())
        BluetoothPrinter.print(container.appContext, address, data)
    }

    suspend fun clearLocal() {
        dao.clearAll()
        // Branding (logo/login background) stays so the login screen keeps the shop look (D21).
        _outlet.value = null; _promos.value = emptyList(); _productOutlets.value = emptyList(); _tenant.value = null
        container.secureStore.put(ENT_KEY, null)
        _ent.value = null
    }

    fun todayStartMs(): Long = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    companion object {
        private const val ENT_KEY = "entitlement"
        const val DAY_MS = 86_400_000L
        private val random = SecureRandom()

        fun uuidV7(): String {
            val ms = System.currentTimeMillis()
            val bytes = ByteArray(16).also { random.nextBytes(it) }
            for (i in 0..5) bytes[i] = (ms shr (40 - 8 * i)).toByte()
            bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x70).toByte()
            bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
            var msb = 0L
            var lsb = 0L
            for (i in 0..7) msb = (msb shl 8) or (bytes[i].toLong() and 0xff)
            for (i in 8..15) lsb = (lsb shl 8) or (bytes[i].toLong() and 0xff)
            return UUID(msb, lsb).toString()
        }
    }
}
