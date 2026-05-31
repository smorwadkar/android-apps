package com.mobildroid.cloudshelf.app.data.s3

import aws.sdk.kotlin.services.s3.model.CopyObjectRequest
import aws.sdk.kotlin.services.s3.model.Delete
import aws.sdk.kotlin.services.s3.model.DeleteObjectRequest
import aws.sdk.kotlin.services.s3.model.DeleteObjectsRequest
import aws.sdk.kotlin.services.s3.model.GetBucketLocationRequest
import aws.sdk.kotlin.services.s3.model.GetObjectRequest
import aws.sdk.kotlin.services.s3.model.ListObjectsV2Request
import aws.sdk.kotlin.services.s3.model.ObjectIdentifier
import aws.sdk.kotlin.services.s3.model.PutObjectRequest
import aws.sdk.kotlin.services.s3.model.ServerSideEncryption
import aws.sdk.kotlin.services.s3.presigners.presignGetObject
import aws.smithy.kotlin.runtime.content.ByteStream
import aws.smithy.kotlin.runtime.content.toInputStream
import aws.smithy.kotlin.runtime.time.Instant
import com.mobildroid.cloudshelf.app.core.s3.S3ClientProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Smithy Kotlin's [Instant] exposes only [Instant.epochSeconds] and
 * [Instant.nanosecondsOfSecond] across SDK versions. Convert to epoch ms
 * once here so the rest of the app deals in plain Longs.
 */
private fun Instant.toEpochMs(): Long =
    epochSeconds * 1_000L + nanosecondsOfSecond / 1_000_000L

/**
 * The single place the rest of the app talks to S3 from.
 *
 * Every method runs on Dispatchers.IO — required because the AWS SDK for
 * Kotlin's HTTP engine does synchronous socket cleanup on the calling thread.
 */
@Singleton
class S3Repository @Inject constructor(
    private val clientProvider: S3ClientProvider,
    private val regionCache: BucketRegionCache
) {

    /** List all buckets visible to the signed-in identity. */
    suspend fun listBuckets(): List<BucketSummary> = withContext(Dispatchers.IO) {
        val client = clientProvider.globalClient()
        val resp = client.listBuckets()
        resp.buckets.orEmpty().mapNotNull { bucket ->
            val name = bucket.name ?: return@mapNotNull null
            BucketSummary(
                name = name,
                creationDateEpochMs = bucket.creationDate?.toEpochMs(),
                region = regionCache.get(name)
            )
        }
    }

    /**
     * Resolve and cache the bucket's region.
     * Handles the historical quirks of GetBucketLocation:
     *   - us-east-1 is reported as null / empty string
     *   - eu-west-1 was historically reported as "EU"
     */
    suspend fun getBucketRegion(bucket: String): String = withContext(Dispatchers.IO) {
        regionCache.get(bucket)?.let { return@withContext it }
        val client = clientProvider.globalClient()
        val resp = client.getBucketLocation(GetBucketLocationRequest { this.bucket = bucket })
        val region = normalizeLocation(resp.locationConstraint?.value)
        regionCache.put(bucket, region)
        region
    }

    /**
     * List one page of items inside [bucket] at [prefix] (use "" for root).
     * Common prefixes (folders) come first in the result, then objects.
     * If [continuationToken] is non-null, fetches the next page.
     */
    suspend fun listObjects(
        bucket: String,
        prefix: String,
        continuationToken: String? = null,
        pageSize: Int = 100
    ): ListPage = withContext(Dispatchers.IO) {
        val region = getBucketRegion(bucket)
        val client = clientProvider.client(region)
        val resp = client.listObjectsV2(ListObjectsV2Request {
            this.bucket = bucket
            this.prefix = prefix.ifEmpty { null }
            this.delimiter = "/"
            this.continuationToken = continuationToken
            this.maxKeys = pageSize
        })

        val folders = resp.commonPrefixes
            .orEmpty()
            .mapNotNull { cp -> cp.prefix?.let { S3Item.Folder(it) } }

        val objects = resp.contents
            .orEmpty()
            .mapNotNull { obj ->
                val key = obj.key ?: return@mapNotNull null
                // S3 sometimes returns the prefix itself as a zero-byte object
                // (the synthetic folder marker). Hide it from the listing.
                if (key == prefix) return@mapNotNull null
                S3Item.Object(
                    key = key,
                    sizeBytes = obj.size ?: 0L,
                    lastModifiedEpochMs = obj.lastModified?.toEpochMs() ?: 0L
                )
            }

        ListPage(
            items = folders + objects,
            nextContinuationToken = resp.nextContinuationToken
        )
    }

    private fun normalizeLocation(constraint: String?): String = when (constraint) {
        null, "", "us-east-1" -> "us-east-1"
        "EU" -> "eu-west-1"
        else -> constraint
    }

    private companion object {
        const val MAX_DELETE_BATCH = 1000
    }

    // ---- Phase 4 preview helpers ----------------------------------------

    /**
     * Generate a short-lived HTTPS URL for [bucket]/[key]. Used by ExoPlayer
     * (which needs a URL it can range-request itself) and Coil (image loader).
     *
     * Default TTL is 15 minutes — long enough to start a video, short enough
     * that leaked URLs don't stay live.
     */
    suspend fun presignGet(
        bucket: String,
        key: String,
        ttl: Duration = 15.minutes
    ): String = withContext(Dispatchers.IO) {
        val region = getBucketRegion(bucket)
        val client = clientProvider.client(region)
        val signed = client.presignGetObject(
            GetObjectRequest {
                this.bucket = bucket
                this.key = key
            },
            ttl
        )
        signed.url.toString()
    }

    /**
     * Fetch up to [maxBytes] of an object as a ByteArray. Used for text preview.
     * Uses Range so we never pull more than we'll render.
     *
     * [onProgress] is invoked per chunk read with (bytesDone, totalBytes).
     * totalBytes is the lesser of contentLength and maxBytes.
     */
    suspend fun fetchBytes(
        bucket: String,
        key: String,
        maxBytes: Int,
        onProgress: (bytesDone: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): ByteArray = withContext(Dispatchers.IO) {
        val region = getBucketRegion(bucket)
        val client = clientProvider.client(region)
        val out = ByteArrayOutputStream()
        client.getObject(GetObjectRequest {
            this.bucket = bucket
            this.key = key
            this.range = "bytes=0-${maxBytes - 1}"
        }) { resp ->
            val total = minOf(resp.contentLength ?: maxBytes.toLong(), maxBytes.toLong())
            val body = resp.body ?: return@getObject
            body.toInputStream().use { input ->
                val buf = ByteArray(8 * 1024)
                while (out.size() < maxBytes) {
                    val n = input.read(buf)
                    if (n < 0) break
                    val take = minOf(n, maxBytes - out.size())
                    out.write(buf, 0, take)
                    onProgress(out.size().toLong(), total)
                }
            }
        }
        out.toByteArray()
    }

    // ---- Phase 5 file operations ----------------------------------------

    /** Delete a single object. */
    suspend fun deleteObject(bucket: String, key: String) = withContext(Dispatchers.IO) {
        val region = getBucketRegion(bucket)
        val client = clientProvider.client(region)
        client.deleteObject(DeleteObjectRequest {
            this.bucket = bucket
            this.key = key
        })
    }

    /**
     * Recursively delete every object under [prefix]. S3 has no real folders —
     * this lists everything with the prefix and DeleteObjects-batches them up
     * to 1000 keys per call.
     *
     * @param onProgress optional callback invoked with (deletedSoFar, totalKnown).
     *        totalKnown grows as we discover more pages, so it isn't known up
     *        front; the UI should treat it as "best guess so far".
     */
    suspend fun deletePrefix(
        bucket: String,
        prefix: String,
        onProgress: (deletedSoFar: Int, totalKnown: Int) -> Unit = { _, _ -> }
    ) = withContext(Dispatchers.IO) {
        val region = getBucketRegion(bucket)
        val client = clientProvider.client(region)
        var continuationToken: String? = null
        var totalDeleted = 0
        do {
            val resp = client.listObjectsV2(ListObjectsV2Request {
                this.bucket = bucket
                this.prefix = prefix
                this.continuationToken = continuationToken
                this.maxKeys = MAX_DELETE_BATCH
            })
            val keys = resp.contents.orEmpty().mapNotNull { it.key }
            if (keys.isNotEmpty()) {
                client.deleteObjects(DeleteObjectsRequest {
                    this.bucket = bucket
                    this.delete = Delete {
                        this.objects = keys.map { k -> ObjectIdentifier { key = k } }
                    }
                })
                totalDeleted += keys.size
                onProgress(totalDeleted, totalDeleted + if (resp.nextContinuationToken != null) MAX_DELETE_BATCH else 0)
            }
            continuationToken = resp.nextContinuationToken
        } while (continuationToken != null)
    }

    /**
     * Server-side copy from [sourceKey] to [destKey] within the same bucket.
     * Preserves server-side encryption.
     */
    suspend fun copyObject(
        bucket: String,
        sourceKey: String,
        destKey: String
    ) = withContext(Dispatchers.IO) {
        val region = getBucketRegion(bucket)
        val client = clientProvider.client(region)
        // CopySource format is `<bucket>/<urlEncodedKey>`. Slashes inside the key
        // must stay as `/`, but everything else (spaces, +, %, unicode) needs encoding.
        val encodedSource = URLEncoder.encode(sourceKey, "UTF-8").replace("%2F", "/").replace("+", "%20")
        client.copyObject(CopyObjectRequest {
            this.bucket = bucket
            this.key = destKey
            this.copySource = "$bucket/$encodedSource"
            this.serverSideEncryption = ServerSideEncryption.Aes256
        })
    }

    /** Rename = server-side copy + delete original. Atomic from the user's POV. */
    suspend fun renameObject(
        bucket: String,
        sourceKey: String,
        destKey: String
    ) = withContext(Dispatchers.IO) {
        copyObject(bucket, sourceKey, destKey)
        deleteObject(bucket, sourceKey)
    }

    /**
     * Create a "folder" — really a zero-byte object whose key ends in `/`.
     * S3 has no folders; this is just a marker that ListObjects with delimiter
     * will surface as a CommonPrefix.
     */
    suspend fun createFolder(bucket: String, prefix: String) = withContext(Dispatchers.IO) {
        val region = getBucketRegion(bucket)
        val client = clientProvider.client(region)
        val key = if (prefix.endsWith('/')) prefix else "$prefix/"
        client.putObject(PutObjectRequest {
            this.bucket = bucket
            this.key = key
            this.body = ByteStream.fromBytes(ByteArray(0))
            this.contentLength = 0L
            this.serverSideEncryption = ServerSideEncryption.Aes256
        })
    }

    /**
     * Download the full object to a file under the app's cache dir. Used for
     * PDF preview (PdfRenderer needs a local ParcelFileDescriptor).
     *
     * [onProgress] is invoked per chunk written with (bytesDone, totalBytes).
     */
    suspend fun downloadToCacheFile(
        bucket: String,
        key: String,
        target: File,
        onProgress: (bytesDone: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ) = withContext(Dispatchers.IO) {
        val region = getBucketRegion(bucket)
        val client = clientProvider.client(region)
        target.parentFile?.mkdirs()
        client.getObject(GetObjectRequest {
            this.bucket = bucket
            this.key = key
        }) { resp ->
            val total = resp.contentLength ?: 0L
            val body = resp.body ?: error("Empty response body.")
            FileOutputStream(target).use { out ->
                body.toInputStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
        }
    }
}
