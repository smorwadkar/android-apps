package com.mobildroid.cloudshelf.app.transfer

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import aws.sdk.kotlin.services.s3.model.GetObjectRequest
import aws.smithy.kotlin.runtime.content.toInputStream
import com.mobildroid.cloudshelf.app.core.notifications.TransferNotifications
import com.mobildroid.cloudshelf.app.core.s3.S3ClientProvider
import com.mobildroid.cloudshelf.app.data.s3.S3ErrorMapper
import com.mobildroid.cloudshelf.app.data.s3.S3Repository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream

/**
 * Downloads a single S3 object to public storage via [DownloadDestination].
 *
 *  - First attempt: full GetObject, write to a fresh MediaStore entry (Q+)
 *    or cache file (pre-Q). The destination URI is persisted to the
 *    [TransferEntity] so retries reuse the same row.
 *  - Retry: open append stream on the existing URI, query its current size,
 *    and GetObject with `Range: bytes=N-` from that size.
 *  - Success: finalize the destination (clears MediaStore IS_PENDING).
 *  - Cancel: delete the pending destination so the user doesn't see a
 *    half-downloaded entry in Gallery / Files.
 */
@HiltWorker
class DownloadWorker @AssistedInject constructor(
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

        // Immediate "Queued" notification so we satisfy the 10-second
        // foreground-service rule even while waiting for a download slot.
        setForeground(
            TransferNotifications.buildForegroundInfo(
                context = appContext,
                notificationId = (id + NOTIFICATION_BASE).toInt(),
                title = "Downloading ${entity.displayName}",
                text = "Queued — waiting for slot",
                percent = null,
                workId = this@DownloadWorker.id
            )
        )

        // Throttle concurrent downloads to avoid the same OkHttp per-host
        // saturation that breaks uploads when a batch is queued at once.
        DOWNLOAD_SEMAPHORE.withPermit {
            transferRepository.setRunning(id)

            // Establish or reuse the destination URI.
            val destinationUri: String = entity.localUri.ifEmpty {
                DownloadDestination.create(
                    context = appContext,
                    displayName = entity.displayName,
                    objectKey = entity.objectKey
                ).also { transferRepository.setLocalUri(id, it) }
            }

            setForeground(
                TransferNotifications.buildForegroundInfo(
                    context = appContext,
                    notificationId = (id + NOTIFICATION_BASE).toInt(),
                    title = "Downloading ${entity.displayName}",
                    text = if (entity.bytesTransferred > 0) "Resuming…" else "Starting…",
                    percent = if (entity.sizeBytes > 0)
                        ((entity.bytesTransferred * 100) / entity.sizeBytes).toInt() else null,
                    workId = this@DownloadWorker.id
                )
            )

            return@withContext doDownload(id, entity, destinationUri)
        }
    }

    private suspend fun doDownload(
        id: Long,
        entity: com.mobildroid.cloudshelf.app.data.db.transfer.TransferEntity,
        destinationUri: String
    ): Result {
        return try {
            // Reconcile DB-recorded bytes against the destination's actual size.
            // If the destination is shorter (e.g., partial flush before crash),
            // back up so the Range request matches reality.
            val actualBytes = DownloadDestination.currentSize(appContext, destinationUri)
            val startAt = minOf(entity.bytesTransferred.coerceAtLeast(0), actualBytes)
            if (startAt < entity.bytesTransferred) {
                transferRepository.updateProgress(id, startAt)
            }
            val mode = if (startAt > 0) "wa" else "w"
            val rangeHeader = if (startAt > 0) "bytes=$startAt-" else null

            val region = s3Repository.getBucketRegion(entity.bucket)
            val s3 = s3ClientProvider.client(region)

            var transferred = startAt
            s3.getObject(GetObjectRequest {
                this.bucket = entity.bucket
                this.key = entity.objectKey
                this.range = rangeHeader
            }) { resp ->
                val body = resp.body ?: error("Empty response body.")
                val total = if (entity.sizeBytes > 0) entity.sizeBytes
                    else (resp.contentLength ?: 0L) + startAt

                DownloadDestination.openOutputStream(appContext, destinationUri, mode).use { out ->
                    body.toInputStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var lastReportedPct = -1
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            transferred += read
                            transferRepository.updateProgress(id, transferred)
                            val pct = if (total > 0) ((transferred * 100) / total).toInt() else 0
                            if (pct != lastReportedPct && pct % 2 == 0) {
                                lastReportedPct = pct
                                setForeground(
                                    TransferNotifications.buildForegroundInfo(
                                        context = appContext,
                                        notificationId = (id + NOTIFICATION_BASE).toInt(),
                                        title = "Downloading",
                                        text = "$pct%",
                                        percent = pct,
                                        workId = this@DownloadWorker.id
                                    )
                                )
                            }
                        }
                    }
                }
            }

            DownloadDestination.finalize(appContext, destinationUri)
            transferRepository.complete(id)
            Result.success()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            DownloadDestination.delete(appContext, destinationUri)
            transferRepository.cancel(id)
            throw cancelled
        } catch (t: Throwable) {
            Timber.w(t, "Download failed for transfer $id")
            transferRepository.fail(id, S3ErrorMapper.toUiMessage(t))
            // Leave the destination URI in place so retry can resume into it.
            Result.retry()
        }
    }

    companion object {
        const val KEY_TRANSFER_ID = "transferId"
        private const val NOTIFICATION_BASE = 200_000L

        // App-wide cap on concurrent active downloads. Excess workers wait
        // here showing "Queued — waiting for slot" instead of all attempting
        // GetObject at once and stalling each other in OkHttp's per-host pool.
        private const val MAX_CONCURRENT_DOWNLOADS = 3
        private val DOWNLOAD_SEMAPHORE = Semaphore(MAX_CONCURRENT_DOWNLOADS)
    }
}
