package com.mobildroid.cloudshelf.app.auth

/**
 * Lightweight, in-memory representation of the user's AWS identity within the app.
 * Persistence and refresh logic live in:
 *   - [IamKeyStore] for the IAM-key path (EncryptedSharedPreferences),
 *   - Amplify's own session cache for the Cognito path.
 *
 * Code outside the auth package should depend on this sealed type, NOT on
 * Amplify or raw IAM types directly.
 */
sealed interface CloudShelfCredentials {

    /** AWS region to use for S3 client construction by default. */
    val defaultRegion: String

    /** A human-readable identifier shown in UI (email, IAM user ARN tail, etc.). */
    val displayName: String

    /**
     * Credentials federated through Cognito User Pool + Identity Pool.
     * The actual key/secret/session token are fetched lazily from Amplify
     * each time S3 needs them (Amplify refreshes automatically).
     */
    data class Cognito(
        val identityId: String,
        val email: String?,
        override val defaultRegion: String
    ) : CloudShelfCredentials {
        override val displayName: String get() = email ?: identityId
    }

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
