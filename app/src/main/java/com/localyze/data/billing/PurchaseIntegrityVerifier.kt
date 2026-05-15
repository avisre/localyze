package com.localyze.data.billing

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.android.play.core.integrity.StandardIntegrityManager
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenProvider
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Verifies app and device integrity using the Play Integrity API,
 * and provides a hook for server-side purchase token verification.
 *
 * Since Localyze does not currently operate a backend, the server-side
 * verification is exposed as an interface that can be wired to a real
 * endpoint before production shipping.
 */
@Singleton
class PurchaseIntegrityVerifier @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private var integrityTokenProvider: StandardIntegrityTokenProvider? = null

    /**
     * Request a Play Integrity token for the current app/device session.
     * The token can be sent to a backend to verify the device is genuine
     * and the app binary has not been tampered with.
     */
    suspend fun requestIntegrityToken(requestHash: String? = null): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val provider = getOrCreateProvider()
                    ?: return@withContext Result.failure(
                        IllegalStateException("Play Integrity not available on this device")
                    )

                val requestBuilder = StandardIntegrityTokenRequest.builder()
                if (!requestHash.isNullOrBlank()) {
                    requestBuilder.setRequestHash(requestHash)
                }
                val request = requestBuilder.build()

                val token = withTimeoutOrNull(10_000L) {
                    suspendCancellableCoroutine { continuation ->
                        provider.request(request)
                            .addOnSuccessListener { continuation.resume(it.token()) }
                            .addOnFailureListener { continuation.resume(null) }
                    }
                }

                if (token != null) {
                    Result.success(token)
                } else {
                    Result.failure(IllegalStateException("Integrity token request timed out or failed"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * Perform a local "best effort" integrity check.
     * Returns true if the token was obtained successfully.
     * A production app should send the token to a backend for verification.
     */
    suspend fun verifyLocalIntegrity(): Boolean {
        return requestIntegrityToken().isSuccess
    }

    private suspend fun getOrCreateProvider(): StandardIntegrityTokenProvider? {
        integrityTokenProvider?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                val manager = com.google.android.play.core.integrity.IntegrityManagerFactory.createStandard(context)
                val provider = withTimeoutOrNull(10_000L) {
                    suspendCancellableCoroutine { continuation ->
                        manager.prepareIntegrityToken(
                            StandardIntegrityManager.PrepareIntegrityTokenRequest.builder()
                                .setCloudProjectNumber(CLOUD_PROJECT_NUMBER)
                                .build()
                        )
                            .addOnSuccessListener { continuation.resume(it) }
                            .addOnFailureListener { continuation.resume(null) }
                    }
                }
                integrityTokenProvider = provider
                provider
            } catch (_: Exception) {
                null
            }
        }
    }

    companion object {
        /** Set to your Firebase / Google Cloud project number before shipping. */
        private const val CLOUD_PROJECT_NUMBER = 123456789L
    }
}

/**
 * Interface for server-side purchase token verification.
 * Implement this with a real backend endpoint before production release.
 */
interface PurchaseTokenVerifier {
    /**
     * Verify a Google Play purchase token with the backend.
     *
     * @param purchaseToken the token from the Play Billing purchase
     * @param productId the subscription product id
     * @return true if the purchase is valid and acknowledged by Google
     */
    suspend fun verifyPurchaseToken(purchaseToken: String, productId: String): Boolean
}

/**
 * Default no-op verifier. Always returns true.
 * Replace with a real implementation before production shipping.
 */
class NoOpPurchaseTokenVerifier @Inject constructor() : PurchaseTokenVerifier {
    override suspend fun verifyPurchaseToken(purchaseToken: String, productId: String): Boolean {
        return true
    }
}
