package com.mobildroid.cloudshelf.app.auth

/**
 * Lightweight, in-memory representation of the user's AWS identity within the app.
 * Persistence lives in [IamKeyStore] (EncryptedSharedPreferences).
 *
 * Code outside the auth package should depend on this sealed type, NOT on
 * raw IAM types directly.
 */
sealed interface CloudShelfCredentials {

    /** AWS region to use for S3 client construction by default. */
    val defaultRegion: String

    /** A human-readable identifier shown in UI (email, IAM user ARN tail, etc.). */
    val displayName: String

    /**
     * Long-lived IAM access key entered by the user.
     * Stored encrypted at rest via [IamKeyStore].
     */
    data class IamKey(
        val accessKeyId: String,
        val secretAccessKey: String,
        val sessionToken: String?,
        val accountId: String?,
        val arn: String?,
        override val defaultRegion: String
    ) : CloudShelfCredentials {
        override val displayName: String get() = arn?.substringAfterLast('/') ?: accessKeyId
    }
}
