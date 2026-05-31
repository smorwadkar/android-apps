package com.mobildroid.cloudshelf.app.core.s3

import aws.sdk.kotlin.services.s3.S3Client
import com.mobildroid.cloudshelf.app.auth.AuthRepository
import com.mobildroid.cloudshelf.app.auth.AwsCredentialsProviderFactory
import com.mobildroid.cloudshelf.app.auth.CloudShelfCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory cache of region -> [S3Client].
 *
 * Why one per region: S3 clients are region-bound. To list a bucket in eu-west-1
 * we need a client configured for eu-west-1; for us-east-1 we need a separate
 * client. Constructing a client per request is expensive (HTTP engine startup,
 * credential resolution wiring), so we cache and reuse.
 *
 * The cache is invalidated whenever the bound credentials change identity
 * (sign-in, sign-out, switch IAM keys), so we never use a stale client.
 */
@Singleton
class S3ClientProvider @Inject constructor(
    private val authRepository: AuthRepository,
    private val credentialsFactory: AwsCredentialsProviderFactory
) {

    private val clientsByRegion = ConcurrentHashMap<String, S3Client>()

    /**
     * The credentials object the cached clients were built against. When this
     * doesn't match the currently signed-in user's credentials we throw the
     * cache away and rebuild — preventing leaks of one user's S3 client to
     * another after a re-sign-in.
     */
    @Volatile
    private var boundCredentials: CloudShelfCredentials? = null

    /**
     * Returns a client for [region]. Builds and caches it on first use.
     * Per the project's dispatcher memory, all SDK construction happens on IO.
     */
    suspend fun client(region: String): S3Client = withContext(Dispatchers.IO) {
        val current = requireSignedInCredentials()
        if (boundCredentials !== current) {
            // Different credentials -> drop and close any existing clients.
            invalidateLocked()
            boundCredentials = current
        }
        clientsByRegion.getOrPut(region) { buildClient(region, current) }
    }

    /**
     * A "global" client for bucket-region-agnostic ops (ListBuckets,
     * GetBucketLocation). us-east-1 is the historical S3 global endpoint and
     * is what GetBucketLocation should be called against.
     */
    suspend fun globalClient(): S3Client = client(GLOBAL_REGION)

    private fun requireSignedInCredentials(): CloudShelfCredentials {
        val state = authRepository.state.value
        return (state as? AuthRepository.AuthState.SignedIn)?.credentials
            ?: error("S3 access requires sign-in.")
    }

    private fun buildClient(region: String, creds: CloudShelfCredentials): S3Client =
        S3Client {
            this.region = region
            this.credentialsProvider = credentialsFactory.create(creds)
        }

    private fun invalidateLocked() {
        clientsByRegion.values.forEach { runCatching { it.close() } }
        clientsByRegion.clear()
    }

    private companion object {
        const val GLOBAL_REGION = "us-east-1"
    }
}
