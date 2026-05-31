package com.mobildroid.cloudshelf.app.data.s3

/**
 * One row in the object browser. Either a synthetic "folder" derived from a
 * common prefix in S3, or an actual object.
 */
sealed interface S3Item {
    val displayName: String

    /**
     * S3 has no real folders. We synthesize them from CommonPrefixes when
     * ListObjectsV2 is called with delimiter = "/".
     *
     * @param prefix The full prefix including trailing slash, e.g. "photos/2024/".
     */
    data class Folder(val prefix: String) : S3Item {
        override val displayName: String
            get() = prefix.removeSuffix("/").substringAfterLast('/')
    }

    /**
     * A real S3 object.
     *
     * @param key Full object key including any prefix.
     * @param sizeBytes Object size in bytes.
     * @param lastModifiedEpochMs Last-modified time in ms since epoch (UTC).
     */
    data class Object(
        val key: String,
        val sizeBytes: Long,
        val lastModifiedEpochMs: Long
    ) : S3Item {
        override val displayName: String
            get() = key.substringAfterLast('/')
    }
}
