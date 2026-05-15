package com.localyze.di

import com.localyze.data.billing.NoOpPurchaseTokenVerifier
import com.localyze.data.billing.PurchaseTokenVerifier
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class BillingModule {

    @Binds
    @Singleton
    abstract fun bindPurchaseTokenVerifier(
        impl: NoOpPurchaseTokenVerifier
    ): PurchaseTokenVerifier
}
