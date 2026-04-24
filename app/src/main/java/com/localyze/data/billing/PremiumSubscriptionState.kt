package com.localyze.data.billing

data class PremiumSubscriptionState(
    val productId: String = BillingConstants.PREMIUM_SUBSCRIPTION_PRODUCT_ID,
    val title: String = BillingConstants.PREMIUM_NAME,
    val formattedPrice: String? = null,
    val isBillingAvailable: Boolean = false,
    val isLoading: Boolean = true,
    val isPurchaseInProgress: Boolean = false,
    val isPremiumActive: Boolean = false,
    val isPending: Boolean = false,
    val statusMessage: String = "Connecting to Google Play",
    val errorMessage: String? = null
) {
    val displayValue: String
        get() = when {
            isPremiumActive -> "Active"
            isPending -> "Pending"
            formattedPrice != null -> formattedPrice
            isLoading -> "Loading"
            else -> "Unavailable"
        }

    val canPurchase: Boolean
        get() = isBillingAvailable &&
                !isLoading &&
                !isPurchaseInProgress &&
                !isPremiumActive &&
                !isPending &&
                formattedPrice != null
}
