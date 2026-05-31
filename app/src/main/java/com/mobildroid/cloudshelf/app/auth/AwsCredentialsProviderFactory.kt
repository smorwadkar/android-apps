package com.mobildroid.cloudshelf.app.auth

import aws.smithy.kotlin.runtime.auth.awscredentials.Credentials
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import aws.smithy.kotlin.runtime.collections.Attributes
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds an AWS SDK for Kotlin [CredentialsProvider] from a [CloudShelfCredentials]
 * value. This is the single bridge between our auth domain and the S3 client.
 *
 * For the IAM-key path, credentials never change for the session, so a
 * pure static provider is enough.
 */
@Singleton
class AwsCredentialsProviderFactory @Inject constructor() {

    fun create(creds: CloudShelfCredentials): CredentialsProvider =
        StaticIamCredentialsProvider(creds as CloudShelfCredentials.IamKey)

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
