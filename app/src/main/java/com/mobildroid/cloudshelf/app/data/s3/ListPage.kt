package com.mobildroid.cloudshelf.app.data.s3

/**
 * One page of [S3Item] results. [nextContinuationToken] is non-null when
 * there are more results to fetch via another ListObjectsV2 call with that
 * token passed back in.
 */
data class ListPage(
    val items: List<S3Item>,
    val nextContinuationToken: String?
)
