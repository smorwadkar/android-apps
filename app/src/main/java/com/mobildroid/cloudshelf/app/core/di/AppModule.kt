package com.mobildroid.cloudshelf.app.core.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Top-level Hilt module. Phase 1+ adds:
 *   - S3ClientProvider (per-region cached aws.sdk.kotlin.services.s3.S3Client)
 *   - CredentialsProvider (Cognito-backed or IAM-key-backed)
 *   - AuthRepository, S3Repository, TransferRepository
 *   - Room database
 *   - EncryptedSharedPreferences wrapper for IAM credential storage
 *
 * Kept intentionally thin in Phase 0 so the Hilt graph compiles with only
 * what the navigation skeleton needs.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideAppContext(@ApplicationContext context: Context): Context = context
}
