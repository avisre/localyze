package com.localyze

import com.localyze.data.billing.PlayPurchaseState
import com.localyze.data.billing.resolvePremiumEntitlement
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PremiumEntitlementPolicyTest {

    @Test
    fun `purchased matching product grants entitlement`() {
        val snapshot = resolvePremiumEntitlement(
            productId = "localyze_premium_yearly",
            purchases = listOf(
                listOf("localyze_premium_yearly") to PlayPurchaseState.Purchased
            )
        )

        assertTrue(snapshot.hasActiveEntitlement)
        assertFalse(snapshot.hasPendingPurchase)
    }

    @Test
    fun `pending matching product does not grant active entitlement`() {
        val snapshot = resolvePremiumEntitlement(
            productId = "localyze_premium_yearly",
            purchases = listOf(
                listOf("localyze_premium_yearly") to PlayPurchaseState.Pending
            )
        )

        assertFalse(snapshot.hasActiveEntitlement)
        assertTrue(snapshot.hasPendingPurchase)
    }

    @Test
    fun `unrelated purchases do not grant entitlement`() {
        val snapshot = resolvePremiumEntitlement(
            productId = "localyze_premium_yearly",
            purchases = listOf(
                listOf("other_subscription") to PlayPurchaseState.Purchased
            )
        )

        assertFalse(snapshot.hasActiveEntitlement)
        assertFalse(snapshot.hasPendingPurchase)
    }

    @Test
    fun `purchased state wins over pending state`() {
        val snapshot = resolvePremiumEntitlement(
            productId = "localyze_premium_yearly",
            purchases = listOf(
                listOf("localyze_premium_yearly") to PlayPurchaseState.Pending,
                listOf("localyze_premium_yearly") to PlayPurchaseState.Purchased
            )
        )

        assertTrue(snapshot.hasActiveEntitlement)
        assertFalse(snapshot.hasPendingPurchase)
    }
}
