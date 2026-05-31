package com.mobildroid.cloudshelf.app.auth

import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.sts.StsClient
import aws.sdk.kotlin.services.sts.model.GetCallerIdentityRequest
import aws.smithy.kotlin.runtime.auth.awscredentials.Credentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

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
}
