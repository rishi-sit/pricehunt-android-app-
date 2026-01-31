package com.pricehunt.di

import com.pricehunt.data.remote.PriceHuntApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    
    /**
     * Base URL for the PriceHunt API.
     * Production: Render deployment with AI-powered smart search
     */
    private const val BASE_URL = "https://pricehunt-hklm.onrender.com/"
    
    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        
        return OkHttpClient.Builder()
            .addInterceptor(logging)
            // Connection pooling for faster subsequent requests
            .connectionPool(ConnectionPool(10, 5, TimeUnit.MINUTES))
            // Enable HTTP/2 for faster multiplexing
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            // Optimized timeouts
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            // Keep connections alive
            .retryOnConnectionFailure(true)
            .build()
    }
    
    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }
    
    @Provides
    @Singleton
    fun providePriceHuntApi(retrofit: Retrofit): PriceHuntApi {
        return retrofit.create(PriceHuntApi::class.java)
    }
}

