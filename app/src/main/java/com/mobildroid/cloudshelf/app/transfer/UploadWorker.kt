package com.mobildroid.cloudshelf.app.transfer

import android.content.Context
import android.net.Uri
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import aws.sdk.kotlin.services.s3.model.AbortMultipartUploadRequest
import aws.sdk.kotlin.services.s3.model.CompleteMultipartUploadRequest
import aws.sdk.kotlin.services.s3.model.CompletedMultipartUpload
import aws.sdk.kotlin.services.s3.model.CompletedPart
import aws.sdk.kotlin.services.s3.model.CreateMultipartUploadRequest
import aws.sdk.kotlin.services.s3.model.ListPartsRequest
import aws.sdk.kotlin.services.s3.model.PutObjectRequest
import aws.sdk.kotlin.services.s3.model.ServerSideEncryption
import aws.sdk.kotlin.services.s3.model.StorageClass
import aws.sdk.kotlin.services.s3.model.UploadPartRequest
import aws.smithy.kotlin.runtime.content.ByteStream
import com.mobildroid.cloudshelf.app.core.notifications.TransferNotifications
import com.mobildroid.cloudshelf.app.core.s3.S3ClientProvider
import com.mobildroid.cloudshelf.app.data.s3.S3ErrorMapper
import com.mobildroid.cloudshelf.app.data.s3.S3Repository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.InputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * Uploads a single SAF URI to S3 with SSE-S3 encryption and Glacier Instant Retrieval storage class.
 *
 *  - Files smaller than [SINGLE_PUT_THRESHOLD] use one PutObject call.
 *  - Larger files use multipart upload with [PART_SIZE_BYTES] parts, up to
 *    [MAX_CONCURRENT_PARTS] in parallel.
 *  - On restart (process killed mid-upload), if the persisted entity already
 *    has an uploadId, we ListParts to skip already-uploaded parts.
 *
 * IAM requirements: s3:PutObject, s3:AbortMultipartUpload,
 * s3:ListMultipartUploadParts. (s3:CreateMultipartUpload + s3:UploadPart are
 * covered by s3:PutObject.)
 */
@HiltWorker
class UploadWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted params: WorkerParameters,
    private val transferRepository: TransferRepository,
    private val s3ClientProvider: S3ClientProvider,
    private val s3Repository: S3Repository
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = inputData.getLong(KEY_TRANSFER_ID, -1L)
        if (id == -1L) return@withContext Result.failure()
        val entity = transferRepository.getEntity(id) ?: return@withContext Result.failure()

        if (entity.status == TransferStatus.CANCELLED.name) return@withContext Result.success()

        // Show a "Queued" notification immediately so we satisfy the 10-second
        // foreground-service rule even if we're waiting for a slot.
        setForeground(
            TransferNotifications.buildForegroundInfo(
                context = appContext,
                notificationId = (id + NOTIFICATION_BASE).toInt(),
                title = "Uploading ${entity.displayName}",
                text = "Queued — waiting for slot",
                percent = null,
                workId = this@UploadWorker.id
            )
        )

        // Throttle concurrent uploads. Without this, hundreds of workers
        // launched in one batch (one per selected file) all try to talk to S3
        // at once and saturate OkHttp's per-host connection pool, causing
        // requests to queue invisibly so the UI looks stuck at "Starting…".
        UPLOAD_SEMAPHORE.withPermit {
            transferRepository.setRunning(id)

            setForeground(
                TransferNotifications.buildForegroundInfo(
                    context = appContext,
                    notificationId = (id + NOTIFICATION_BASE).toInt(),
                    title = "Uploading ${entity.displayName}",
                    text = "Starting…",
                    percent = 0,
                    workId = this@UploadWorker.id
                )
            )

            return@withContext doUpload(id, entity)
        }
    }

    private suspend fun doUpload(
        id: Long,
        entity: com.mobildroid.cloudshelf.app.data.db.transfer.TransferEntity
    ): Result {
        return try {
            val region = s3Repository.getBucketRegion(entity.bucket)
            val s3 = s3ClientProvider.client(region)
            val uri = Uri.parse(entity.localUri)

            if (entity.sizeBytes in 1..SINGLE_PUT_THRESHOLD) {
                singlePut(s3, entity.bucket, entity.objectKey, uri, entity.sizeBytes, id)
            } else {
                multipart(s3, entity.bucket, entity.objectKey, uri, entity.sizeBytes, id, entity.uploadId)
            }

            transferRepository.complete(id)
            Result.success()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            transferRepository.cancel(id)
            transferRepository.getEntity(id)?.uploadId?.let { uploadId ->
                runCatching {
                    val region = s3Repository.getBucketRegion(entity.bucket)
                    s3ClientProvider.client(region).abortMultipartUpload(
                        AbortMultipartUploadRequest {
                            bucket = entity.bucket
                            key = entity.objectKey
                            this.uploadId = uploadId
                        }
                    )
                    transferRepository.setUploadId(id, null)
                }
            }
            throw cancelled
        } catch (t: Throwable) {
            Timber.w(t, "Upload failed for transfer $id")
            transferRepository.fail(id, S3ErrorMapper.toUiMessage(t))
            Result.failure()
        }
    }

    // ---- single PUT ------------------------------------------------------

    private suspend fun singlePut(
        s3: aws.sdk.kotlin.services.s3.S3Client,
        bucket: String,
        key: String,
        uri: Uri,
        sizeBytes: Long,
        transferId: Long
    ) {
        val bytes = readAllBytes(uri)
        s3.putObject(PutObjectRequest {
            this.bucket = bucket
            this.key = key
            this.body = ByteStream.fromBytes(bytes)
            this.contentLength = sizeBytes
            this.serverSideEncryption = ServerSideEncryption.Aes256
            this.storageClass = StorageClass.GlacierIr
        })
        transferRepository.updateProgress(transferId, sizeBytes)
        updateProgressNotification(transferId, "Uploading", sizeBytes, sizeBytes)
    }

    // ---- multipart ------------------------------------------------------

    private suspend fun multipart(
        s3: aws.sdk.kotlin.services.s3.S3Client,
        bucket: String,
        key: String,
        uri: Uri,
        totalSize: Long,
        transferId: Long,
        existingUploadId: String?
    ) {
        val uploadId = existingUploadId ?: run {
            val resp = s3.createMultipartUpload(CreateMultipartUploadRequest {
                this.bucket = bucket
                this.key = key
                this.serverSideEncryption = ServerSideEncryption.Aes256
                this.storageClass = StorageClass.GlacierIr
            })
            val id = resp.uploadId ?: error("Server didn't return an uploadId.")
            transferRepository.setUploadId(transferId, id)
            id
        }

        val totalParts = ((totalSize + PART_SIZE_BYTES - 1) / PART_SIZE_BYTES).toInt()
        require(totalParts <= MAX_PARTS) {
            "File too large for current part size; would require $totalParts parts (max $MAX_PARTS)."
        }

        // Find parts already uploaded (resume).
        val alreadyDone: Map<Int, CompletedPart> = if (existingUploadId != null) {
            val resp = s3.listParts(ListPartsRequest {
                this.bucket = bucket
                this.key = key
                this.uploadId = uploadId
            })
            resp.parts.orEmpty().mapNotNull { p ->
                val pn = p.partNumber ?: return@mapNotNull null
                pn to CompletedPart { partNumber = pn; eTag = p.eTag }
            }.toMap()
        } else emptyMap()

        val bytesDone = AtomicLong(alreadyDone.keys.sumOf { partSize(it, totalSize, totalParts) })
        transferRepository.updateProgress(transferId, bytesDone.get())

        val completedParts: MutableMap<Int, CompletedPart> = alreadyDone.toMutableMap()
        val semaphore = Semaphore(MAX_CONCURRENT_PARTS)

        coroutineScope {
            (1..totalParts)
                .filter { it !in alreadyDone }
                .map { partNumber ->
                    async {
                        semaphore.withPermit {
                            val offset = (partNumber - 1).toLong() * PART_SIZE_BYTES
                            val size = partSize(partNumber, totalSize, totalParts)
                            val partBytes = readRange(uri, offset, size)
                            val resp = s3.uploadPart(UploadPartRequest {
                                this.bucket = bucket
                                this.key = key
                                this.uploadId = uploadId
                                this.partNumber = partNumber
                                this.contentLength = size
                                this.body = ByteStream.fromBytes(partBytes)
                            })
                            val cp = CompletedPart {
                                this.partNumber = partNumber
                                this.eTag = resp.eTag
                            }
                            synchronized(completedParts) { completedParts[partNumber] = cp }
                            val newTotal = bytesDone.addAndGet(size)
                            transferRepository.updateProgress(transferId, newTotal)
                            updateProgressNotification(
                                transferId = transferId,
                                title = "Uploading",
                                bytesDone = newTotal,
                                totalBytes = totalSize
                            )
                        }
                    }
                }
                .awaitAll()
        }

        s3.completeMultipartUpload(CompleteMultipartUploadRequest {
            this.bucket = bucket
            this.key = key
            this.uploadId = uploadId
            this.multipartUpload = CompletedMultipartUpload {
                this.parts = completedParts.entries.sortedBy { it.key }.map { it.value }
            }
        })
        transferRepository.setUploadId(transferId, null)
    }

    private fun partSize(partNumber: Int, totalSize: Long, totalParts: Int): Long =
        if (partNumber < totalParts) PART_SIZE_BYTES
        else totalSize - (partNumber - 1).toLong() * PART_SIZE_BYTES

    private suspend fun updateProgressNotification(
        transferId: Long,
        title: String,
        bytesDone: Long,
        totalBytes: Long
    ) {
        val pct = if (totalBytes > 0) ((bytesDone * 100) / totalBytes).toInt() else 0
        setForeground(
            TransferNotifications.buildForegroundInfo(
                context = appContext,
                notificationId = (transferId + NOTIFICATION_BASE).toInt(),
                title = title,
                text = "$pct%",
                percent = pct,
                workId = id
            )
        )
    }

    // ---- IO helpers -----------------------------------------------------

    private fun readAllBytes(uri: Uri): ByteArray =
        appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Could not open $uri")

    private fun readRange(uri: Uri, offset: Long, length: Long): ByteArray {
        val stream: InputStream = appContext.contentResolver.openInputStream(uri)
            ?: error("Could not open $uri")
        stream.use { s ->
            var remainingToSkip = offset
            while (remainingToSkip > 0) {
                val skipped = s.skip(remainingToSkip)
                if (skipped <= 0) break
                remainingToSkip -= skipped
            }
            val buf = ByteArray(length.toInt())
            var read = 0
            while (read < buf.size) {
                val n = s.read(buf, read, buf.size - read)
                if (n < 0) break
                read += n
            }
            require(read.toLong() == length) {
                "Short read at offset $offset: got $read of $length"
            }
            return buf
        }
    }

    companion object {
        const val KEY_TRANSFER_ID = "transferId"

        // Notification IDs derived from transfer.id + this base to avoid colliding
        // with other notification IDs used by the app.
        private const val NOTIFICATION_BASE = 100_000L

        private const val SINGLE_PUT_THRESHOLD = 8L * 1024 * 1024
        private const val PART_SIZE_BYTES = 8L * 1024 * 1024
        // Per-upload multipart concurrency. Two parts per worker × three
        // concurrent workers (see UPLOAD_SEMAPHORE) stays comfortably inside
        // OkHttp's default per-host connection cap.
        private const val MAX_CONCURRENT_PARTS = 2
        private const val MAX_PARTS = 10_000

        // App-wide cap on how many UploadWorkers run their actual upload at
        // the same time. Without this, a batch of 20+ workers all hit S3 in
        // parallel and saturate OkHttp's per-host pool, stalling every job.
        private const val MAX_CONCURRENT_UPLOADS = 3
        private val UPLOAD_SEMAPHORE = Semaphore(MAX_CONCURRENT_UPLOADS)
    }
}
