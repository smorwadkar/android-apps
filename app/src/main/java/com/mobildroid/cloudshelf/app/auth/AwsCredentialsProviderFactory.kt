package com.mobildroid.cloudshelf.app.auth

import aws.smithy.kotlin.runtime.auth.awscredentials.Credentials
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import aws.smithy.kotlin.runtime.collections.Attributes
import com.amplifyframework.auth.AWSCredentials
import com.amplifyframework.auth.AWSTemporaryCredentials
import com.amplifyframework.auth.cognito.AWSCognitoAuthSession
import com.amplifyframework.kotlin.core.Amplify
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds an AWS SDK for Kotlin [CredentialsProvider] from a [CloudShelfCredentials]
 * value. This is the single bridge between our auth domain and the S3 client.
 *
 * IMPORTANT: For the Cognito path we re-fetch the session on every resolve()
 * so Amplify can transparently refresh expired temp credentials. We never
 * cache the raw key/secret outside of Amplify.
 */
@Singleton
class AwsCredentialsProviderFactory @Inject constructor() {

    fun create(creds: CloudShelfCredentials): CredentialsProvider = when (creds) {
        is CloudShelfCredentials.Cognito -> AmplifyCredentialsProvider()
        is CloudShelfCredentials.IamKey -> StaticIamCredentialsProvider(creds)
    }

    /**
     * Resolves AWS credentials by asking Amplify for the current Cognito auth
     * session every time. Amplify handles refresh; we just translate types.
     */
    private class AmplifyCredentialsProvider : CredentialsProvider {
        override suspend fun resolve(attributes: Attributes): Credentials {
            val session = Amplify.Auth.fetchAuthSession() as AWSCognitoAuthSession
            val result = session.awsCredentialsResult
            val amplifyCreds = result.value
                ?: error("No AWS credentials available from Cognito session: ${result.error?.message}")
            return amplifyCreds.toAwsSdkCredentials()
        }

        private fun AWSCredentials.toAwsSdkCredentials(): Credentials = when (this) {
            is AWSTemporaryCredentials -> Credentials(
                accessKeyId = accessKeyId,
                secretAccessKey = secretAccessKey,
                sessionToken = sessionToken
            )
            else -> Credentials(
                accessKeyId = accessKeyId,
                secretAccessKey = secretAccessKey
            )
        }
    }

    /**
     * For the IAM-key path, credentials never change for the session, so a
     * pure static provider is enough.
     */
    private class StaticIamCredentialsProvider(
        private val creds: CloudShelfCredentials.IamKey
    ) : CredentialsProvider {
        override suspend fun resolve(attributes: Attributes): Credentials =
            Credentials(
                accessKeyId = creds.accessKeyId,
                secretAccessKey = creds.secretAccessKey,
                sessionToken = creds.sessionToken
            )
    }
}
