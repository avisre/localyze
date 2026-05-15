package com.localyze.data.billing

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlayBillingRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val integrityVerifier: PurchaseIntegrityVerifier,
    private val purchaseTokenVerifier: PurchaseTokenVerifier
) : PurchasesUpdatedListener {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val productId = BillingConstants.PREMIUM_SUBSCRIPTION_PRODUCT_ID

    private val billingClient: BillingClient = BillingClient.newBuilder(appContext)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder()
                .enableOneTimeProducts()
                .build()
        )
        .build()

    private val _state = MutableStateFlow(PremiumSubscriptionState())
    val state: StateFlow<PremiumSubscriptionState> = _state.asStateFlow()

    @Volatile
    private var isConnecting = false

    @Volatile
    private var latestProductDetails: ProductDetails? = null

    init {
        refresh()
    }

    fun refresh() {
        _state.update {
            it.copy(
                isLoading = true,
                statusMessage = "Connecting to Google Play",
                errorMessage = null
            )
        }
        ensureConnected {
            queryProductDetails()
            queryActivePurchases()
        }
    }

    fun restorePurchases() {
        _state.update {
            it.copy(
                isLoading = true,
                statusMessage = "Checking Google Play purchases",
                errorMessage = null
            )
        }
        ensureConnected { queryActivePurchases(restoredByUser = true) }
    }

    fun launchPremiumPurchase(activity: Activity) {
        _state.update {
            it.copy(
                isPurchaseInProgress = true,
                errorMessage = null,
                statusMessage = "Opening Google Play checkout"
            )
        }
        ensureConnected {
            queryProductDetails {
                val productDetails = latestProductDetails
                val offerToken = productDetails?.subscriptionOfferDetails
                    ?.firstOrNull()
                    ?.offerToken

                if (productDetails == null || offerToken.isNullOrBlank()) {
                    _state.update {
                        it.copy(
                            isPurchaseInProgress = false,
                            isLoading = false,
                            statusMessage = "Premium is unavailable",
                            errorMessage = "Premium is not available from Google Play yet. Confirm product ID '$productId' is active in Play Console."
                        )
                    }
                    return@queryProductDetails
                }

                mainHandler.post {
                    val productDetailsParams = BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(productDetails)
                        .setOfferToken(offerToken)
                        .build()
                    val billingFlowParams = BillingFlowParams.newBuilder()
                        .setProductDetailsParamsList(listOf(productDetailsParams))
                        .build()
                    val billingResult = billingClient.launchBillingFlow(activity, billingFlowParams)
                    if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                        _state.update {
                            it.copy(
                                isPurchaseInProgress = false,
                                isLoading = false,
                                statusMessage = "Checkout unavailable",
                                errorMessage = billingResult.userFacingMessage("Could not open Google Play checkout")
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: MutableList<Purchase>?) {
        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                if (!purchases.isNullOrEmpty()) {
                    processPurchases(purchases, fromPurchaseFlow = true)
                } else {
                    queryActivePurchases()
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                _state.update {
                    it.copy(
                        isPurchaseInProgress = false,
                        isLoading = false,
                        statusMessage = if (it.isPremiumActive) "Premium active" else "Purchase cancelled",
                        errorMessage = null
                    )
                }
            }
            else -> {
                _state.update {
                    it.copy(
                        isPurchaseInProgress = false,
                        isLoading = false,
                        statusMessage = "Purchase failed",
                        errorMessage = billingResult.userFacingMessage("Google Play purchase failed")
                    )
                }
            }
        }
    }

    private fun ensureConnected(onConnected: () -> Unit) {
        if (billingClient.isReady) {
            onConnected()
            return
        }
        if (isConnecting) return
        isConnecting = true
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                isConnecting = false
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    _state.update {
                        it.copy(
                            isBillingAvailable = true,
                            statusMessage = "Connected to Google Play",
                            errorMessage = null
                        )
                    }
                    onConnected()
                } else {
                    _state.update {
                        it.copy(
                            isBillingAvailable = false,
                            isLoading = false,
                            isPurchaseInProgress = false,
                            statusMessage = "Google Play Billing unavailable",
                            errorMessage = billingResult.userFacingMessage("Google Play Billing is unavailable")
                        )
                    }
                }
            }

            override fun onBillingServiceDisconnected() {
                isConnecting = false
                _state.update {
                    it.copy(
                        isBillingAvailable = false,
                        isLoading = false,
                        isPurchaseInProgress = false,
                        statusMessage = "Google Play disconnected",
                        errorMessage = "Google Play Billing disconnected. Try again."
                    )
                }
            }
        })
    }

    private fun queryProductDetails(onComplete: (() -> Unit)? = null) {
        val product = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(productId)
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(listOf(product))
            .build()

        billingClient.queryProductDetailsAsync(params) { billingResult, productDetailsResult ->
            if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                latestProductDetails = null
                _state.update {
                    it.copy(
                        isLoading = false,
                        formattedPrice = null,
                        statusMessage = "Premium unavailable",
                        errorMessage = billingResult.userFacingMessage("Could not load premium subscription")
                    )
                }
                onComplete?.invoke()
                return@queryProductDetailsAsync
            }

            val productDetails = productDetailsResult.productDetailsList.firstOrNull {
                it.productId == productId
            }
            latestProductDetails = productDetails

            val price = productDetails?.recurringPrice()
            _state.update {
                it.copy(
                    isBillingAvailable = true,
                    isLoading = false,
                    title = productDetails?.title?.substringBefore(" (") ?: BillingConstants.PREMIUM_NAME,
                    formattedPrice = price,
                    statusMessage = when {
                        it.isPremiumActive -> "Premium active"
                        it.isPending -> "Purchase pending"
                        price != null -> "Billing is handled securely by Google Play"
                        else -> "Premium is not available in Google Play"
                    },
                    errorMessage = if (price == null) {
                        "No active Google Play subscription product was returned for '$productId'."
                    } else {
                        null
                    }
                )
            }
            onComplete?.invoke()
        }
    }

    private fun queryActivePurchases(restoredByUser: Boolean = false) {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()

        billingClient.queryPurchasesAsync(params) { billingResult, purchases ->
            if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                _state.update {
                    it.copy(
                        isLoading = false,
                        isPurchaseInProgress = false,
                        statusMessage = "Could not check purchases",
                        errorMessage = billingResult.userFacingMessage("Could not check Google Play purchases")
                    )
                }
                return@queryPurchasesAsync
            }
            processPurchases(purchases, restoredByUser = restoredByUser)
        }
    }

    private fun processPurchases(
        purchases: List<Purchase>,
        fromPurchaseFlow: Boolean = false,
        restoredByUser: Boolean = false
    ) {
        purchases
            .filter { productId in it.products && it.purchaseState == Purchase.PurchaseState.PURCHASED && !it.isAcknowledged }
            .forEach { purchase -> acknowledgePurchase(purchase) }

        val snapshot = resolvePremiumEntitlement(
            productId = productId,
            purchases = purchases.map { purchase ->
                purchase.products to when (purchase.purchaseState) {
                    Purchase.PurchaseState.PURCHASED -> PlayPurchaseState.Purchased
                    Purchase.PurchaseState.PENDING -> PlayPurchaseState.Pending
                    else -> PlayPurchaseState.Other
                }
            }
        )

        val activePurchase = purchases.firstOrNull {
            productId in it.products && it.purchaseState == Purchase.PurchaseState.PURCHASED
        }

        // Perform server-side token verification and integrity checks
        if (activePurchase != null && snapshot.hasActiveEntitlement) {
            coroutineScope.launch {
                verifyPurchaseSecurely(activePurchase)
            }
        } else {
            updatePurchaseState(snapshot, fromPurchaseFlow, restoredByUser, isVerified = true)
        }
    }

    private suspend fun verifyPurchaseSecurely(purchase: Purchase) {
        val currentState = _state.value
        val tokenVerified = try {
            purchaseTokenVerifier.verifyPurchaseToken(purchase.purchaseToken, productId)
        } catch (_: Exception) {
            false
        }
        val integrityVerified = try {
            integrityVerifier.verifyLocalIntegrity()
        } catch (_: Exception) {
            false
        }
        val fullyVerified = tokenVerified || integrityVerified
        val snapshot = resolvePremiumEntitlement(
            productId = productId,
            purchases = listOf(listOf(productId) to PlayPurchaseState.Purchased)
        )
        _state.update {
            it.copy(
                isBillingAvailable = true,
                isLoading = false,
                isPurchaseInProgress = false,
                isPremiumActive = snapshot.hasActiveEntitlement && fullyVerified,
                isPending = snapshot.hasPendingPurchase,
                isVerified = fullyVerified,
                statusMessage = when {
                    snapshot.hasActiveEntitlement && fullyVerified -> "Premium active"
                    snapshot.hasActiveEntitlement -> "Premium active (verification pending)"
                    snapshot.hasPendingPurchase -> "Purchase pending in Google Play"
                    currentState.isPremiumActive -> "Premium active"
                    else -> "Premium is not available in Google Play"
                },
                errorMessage = if (!fullyVerified && snapshot.hasActiveEntitlement) {
                    "Purchase could not be fully verified. Some premium features may be restricted."
                } else {
                    null
                }
            )
        }
    }

    private fun updatePurchaseState(
        snapshot: PurchaseEntitlementSnapshot,
        fromPurchaseFlow: Boolean,
        restoredByUser: Boolean,
        isVerified: Boolean
    ) {
        _state.update {
            it.copy(
                isBillingAvailable = true,
                isLoading = false,
                isPurchaseInProgress = false,
                isPremiumActive = snapshot.hasActiveEntitlement,
                isPending = snapshot.hasPendingPurchase,
                isVerified = isVerified,
                statusMessage = when {
                    snapshot.hasActiveEntitlement && fromPurchaseFlow -> "Premium activated"
                    snapshot.hasActiveEntitlement -> "Premium active"
                    snapshot.hasPendingPurchase -> "Purchase pending in Google Play"
                    restoredByUser -> "No active premium subscription found"
                    it.formattedPrice != null -> "Billing is handled securely by Google Play"
                    else -> "Premium is not available in Google Play"
                },
                errorMessage = if (restoredByUser && !snapshot.hasActiveEntitlement && !snapshot.hasPendingPurchase) {
                    "No active premium subscription was found on this Google Play account."
                } else {
                    null
                }
            )
        }
    }

    private fun acknowledgePurchase(purchase: Purchase) {
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        billingClient.acknowledgePurchase(params) { result ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                Log.w(TAG, "Failed to acknowledge premium purchase: ${result.debugMessage}")
                _state.update {
                    it.copy(
                        errorMessage = result.userFacingMessage("Premium purchase needs acknowledgement")
                    )
                }
            }
        }
    }

    private fun ProductDetails.recurringPrice(): String? {
        val pricingPhases = subscriptionOfferDetails
            ?.firstOrNull()
            ?.pricingPhases
            ?.pricingPhaseList
            .orEmpty()
        return pricingPhases.lastOrNull { it.formattedPrice.isNotBlank() }?.formattedPrice
            ?: oneTimePurchaseOfferDetails?.formattedPrice
    }

    private fun BillingResult.userFacingMessage(prefix: String): String {
        return if (debugMessage.isBlank()) {
            "$prefix. Response code: $responseCode."
        } else {
            "$prefix: $debugMessage"
        }
    }

    companion object {
        private const val TAG = "PlayBillingRepository"
    }
}
