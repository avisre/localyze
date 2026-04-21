package com.localassistant.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            // Extended timeouts for large file downloads
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)  // 5 min for large chunks
            .writeTimeout(300, TimeUnit.SECONDS)
            // Connection pool for parallel requests
            .connectionPool(okhttp3.ConnectionPool(10, 5, TimeUnit.MINUTES))
            // Follow redirects
            .followRedirects(true)
            .followSslRedirects(true)
            // Larger buffer for faster transfers
            .protocols(listOf(okhttp3.Protocol.HTTP_2, okhttp3.Protocol.HTTP_1_1))
            .build()
    }

    @Provides
    @Singleton
    fun provideModelsDir(@ApplicationContext context: Context): File {
        return File(context.filesDir, "models").also { it.mkdirs() }
    }

    @Provides
    @Singleton
    fun provideCacheDir(@ApplicationContext context: Context): File {
        return File(context.cacheDir, "ai_cache").also { it.mkdirs() }
    }
}