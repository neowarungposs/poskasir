package com.neowarungposs.poskasir.core

import kotlinx.serialization.Serializable

// Field names are camelCase here; AppJson maps them to snake_case columns.

@Serializable
data class Entitlement(
    val serverNow: String,
    val tenantId: String,
    val customerCode: String,
    val payCode: Int,
    val trialEndsAt: String,
    val paidUntil: String? = null,
    val accessUntil: String,
    val graceUntil: String,
    val status: String,
    val maxOfflineDays: Int,
)

@Serializable
data class Tenant(
    val id: String,
    val businessName: String,
    val customerCode: String,
    val email: String? = null,
    val phoneWa: String? = null,
    val waOptIn: Boolean = false,
    val payCode: Int,
    val trialEndsAt: String,
    val paidUntil: String? = null,
    val lifecycle: String = "LIVE",
    val churnedAt: String? = null,
    val churnReason: String? = null,
    val createdAt: String? = null,
)

@Serializable
data class Outlet(
    val id: String,
    val tenantId: String,
    val code: String,
    val name: String,
    val address: String? = null,
    val merchantQrisPath: String? = null,
    val isMain: Boolean = false,
)

@Serializable
data class ProductDto(
    val id: String,
    val tenantId: String? = null,
    val outletId: String? = null,
    val name: String,
    val category: String? = null,
    val price: Long,
    val imagePath: String? = null,
    val isActive: Boolean = true,
    val deletedAt: String? = null,
    val updatedAt: String? = null,
)

@Serializable
data class Plan(
    val id: String,
    val months: Int,
    val normalPrice: Long,
    val discountPct: Double = 0.0,
    val price: Long,
    val badge: String? = null,
    val promoEndsAt: String? = null,
    val isActive: Boolean = true,
)

@Serializable
data class SystemSettings(
    val monthlyBasePrice: Long = 29000,
    val globalQrisPath: String? = null,
    val bankDetailsText: String? = null,
    val graceDays: Int = 3,
    val maxOfflineDays: Int = 7,
    val paymentPageUrl: String? = null,
    val deletionPageUrl: String? = null,
)

@Serializable
data class Membership(
    val userId: String,
    val tenantId: String? = null,
    val outletId: String? = null,
    val role: String,
)
