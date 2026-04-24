package com.localyze.data.billing

enum class PlayPurchaseState {
    Purchased,
    Pending,
    Other
}

data class PurchaseEntitlementSnapshot(
    val hasActiveEntitlement: Boolean,
    val hasPendingPurchase: Boolean
)

fun resolvePremiumEntitlement(
    productId: String,
    purchases: List<Pair<List<String>, PlayPurchaseState>>
): PurchaseEntitlementSnapshot {
    var active = false
    var pending = false

    purchases.forEach { (products, state) ->
        if (productId !in products) return@forEach
        when (state) {
            PlayPurchaseState.Purchased -> active = true
            PlayPurchaseState.Pending -> pending = true
            PlayPurchaseState.Other -> Unit
        }
    }

    return PurchaseEntitlementSnapshot(
        hasActiveEntitlement = active,
        hasPendingPurchase = pending && !active
    )
}
