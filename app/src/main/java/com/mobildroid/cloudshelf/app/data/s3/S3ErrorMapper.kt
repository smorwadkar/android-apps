package com.mobildroid.cloudshelf.app.data.s3

import aws.sdk.kotlin.services.s3.model.NoSuchBucket
import aws.sdk.kotlin.services.s3.model.S3Exception
import java.io.IOException

/**
 * Turns AWS / network exceptions into copy that's safe to show in the UI.
 * Request IDs and ARNs go to Timber for the developer, not to the screen.
 */
object S3ErrorMapper {

    fun toUiMessage(t: Throwable): String = when (t) {
        is NoSuchBucket -> "That bucket doesn't exist or you don't have access."
        is S3Exception -> {
            when (val code = t.sdkErrorMetadata.errorCode) {
                "AccessDenied" -> "You don't have permission for this. Check your IAM policy."
                "NoSuchKey" -> "That object isn't there anymore."
                "InvalidAccessKeyId", "SignatureDoesNotMatch" ->
                    "Your credentials were rejected by AWS. Sign in again."
                "ExpiredToken", "TokenRefreshRequired" ->
                    "Your session expired. Sign in again."
                else -> "AWS error: ${t.message ?: code ?: "unknown"}"
            }
        }
        is IOException -> "Network error. Check your connection and try again."
        else -> t.localizedMessage ?: "Something went wrong."
    }
}
