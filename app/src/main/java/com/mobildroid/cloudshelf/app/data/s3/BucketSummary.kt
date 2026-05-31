package com.mobildroid.cloudshelf.app.data.s3

/**
 * A row on the Buckets screen.
 *
 * @param creationDateEpochMs When the bucket was created, in ms since epoch.
 *        Null if S3 didn't return one.
 * @param region The bucket's region. Lazily resolved via GetBucketLocation
 *        and cached. Null while still loading.
 */
data class BucketSummary(
    val name: String,
    val creationDateEpochMs: Long?,
    val region: String?
)
