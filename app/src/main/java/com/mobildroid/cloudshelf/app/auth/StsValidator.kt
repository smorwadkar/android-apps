package com.mobildroid.cloudshelf.app.auth

import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.sts.StsClient
import aws.sdk.kotlin.services.sts.model.GetCallerIdentityRequest
import aws.smithy.kotlin.runtime.auth.awscredentials.Credentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

/**
 * Thrown when [StsValidator.validate] cannot get a GetCallerIdentity response
 * within the request budget. Distinct from invalid-key errors so the UI can
 * say "check your network" instead of "bad keys".
 */
class IamValidationTimeoutException(message: String) : Exception(message)

/**
 * Validates raw IAM credentials by calling sts:GetCallerIdentity.
 *
 * This is the only AWS call we make BEFORE the credentials are accepted,
 * so it doubles as:
 *   - a "do these keys actually work?" check
 *   - a way to surface the account ID + caller ARN in the UI / logs
 *
 * GetCallerIdentity requires no IAM permission — every authenticated principal
 * can call it. That makes it the safest probe.
 */
@Singleton
class StsValidator @Inject constructor() {

    /**
     * @return The validated IAM credentials enriched with account/arn metadata
     *         from STS, ready to hand to [IamKeyStore].
     * @throws Exception if the keys are invalid, the region is wrong, or the
     *         device has no network. Caller is responsible for surfacing this
     *         as user-friendly error copy.
     */
    suspend fun validate(
        accessKeyId: String,
        secretAccessKey: String,
        sessionToken: String?,
        region: String
    ): CloudShelfCredentials.IamKey = withContext(Dispatchers.IO) {
        // Dispatchers.IO is required because the SDK's HTTP engine performs
        // synchronous socket teardown on the calling thread when StsClient
        // .close() runs at the end of the `use {}` block. On Dispatchers.Main
        // that trips StrictMode's NetworkOnMainThreadException.
        // withTimeoutOrNull bounds the TOTAL validation — including DNS lookup,
        // connect, and the SDK's internal retries — so a dead network surfaces
        // as a friendly error instead of an infinite spinner. Engine timeouts
        // below bound each individual connection attempt.
        val validated = withTimeoutOrNull(REQUEST_TIMEOUT) {
            val provider = StaticCredentialsProvider(
                Credentials(
                    accessKeyId = accessKeyId,
                    secretAccessKey = secretAccessKey,
                    sessionToken = sessionToken
                )
            )
            StsClient {
                this.region = region
                this.credentialsProvider = provider
                httpClient {
                    connectTimeout = CONNECT_TIMEOUT
                    socketReadTimeout = READ_TIMEOUT
                    socketWriteTimeout = WRITE_TIMEOUT
                }
            }.use { sts ->
                val resp = sts.getCallerIdentity(GetCallerIdentityRequest {})
                CloudShelfCredentials.IamKey(
                    accessKeyId = accessKeyId,
                    secretAccessKey = secretAccessKey,
                    sessionToken = sessionToken,
                    accountId = resp.account,
                    arn = resp.arn,
                    defaultRegion = region
                )
            }
        }
        validated ?: throw IamValidationTimeoutException(
            "Timed out contacting AWS after ${REQUEST_TIMEOUT.inWholeSeconds}s. " +
                "Check your internet connection and try again."
        )
    }

    private companion object {
        val CONNECT_TIMEOUT = 10.seconds
        val READ_TIMEOUT = 15.seconds
        val WRITE_TIMEOUT = 15.seconds
        val REQUEST_TIMEOUT = 15.seconds
    }
}
