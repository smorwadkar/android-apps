package com.mobildroid.cloudshelf.app.transfer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Public API that the UI calls to start uploads and downloads.
 * Bridges Room-backed [TransferRepository] with WorkManager.
 */
@Singleton
class TransferScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val transferRepository: TransferRepository
) {

    /**
     * Enqueue an upload of [uri] to s3://[bucket]/[prefix][filename].
     * Returns the transfer id (Room PK).
     */
    suspend fun enqueueUpload(
        bucket: String,
        prefix: String,
        uri: Uri
    ): Long {
        val (displayName, size) = queryUriMetadata(uri)
        val objectKey = prefix.trimStart('/').let { p -> if (p.isEmpty()) displayName else "$p$displayName" }
        val id = transferRepository.enqueue(
            type = TransferType.UPLOAD,
            bucket = bucket,
            objectKey = objectKey,
            localUri = uri.toString(),
            displayName = displayName,
            sizeBytes = size
        )
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(Data.Builder().putLong(UploadWorker.KEY_TRANSFER_ID, id).build())
            .setConstraints(networkConstraints())
            .addTag(TAG_TRANSFER)
            .build()
        transferRepository.setWorkId(id, request.id.toString())
        WorkManager.getInstance(context).enqueue(request)
        return id
    }

    /**
     * Enqueue a download of s3://[bucket]/[objectKey] to app-private storage.
     * Display name is the trailing key segment.
     */
    suspend fun enqueueDownload(
        bucket: String,
        objectKey: String,
        knownSizeBytes: Long
    ): Long {
        val displayName = objectKey.substringAfterLast('/').ifEmpty { objectKey }
        val id = transferRepository.enqueue(
            type = TransferType.DOWNLOAD,
            bucket = bucket,
            objectKey = objectKey,
            localUri = "",                // populated by worker once outFile is known
            displayName = displayName,
            sizeBytes = knownSizeBytes
        )
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(Data.Builder().putLong(DownloadWorker.KEY_TRANSFER_ID, id).build())
            .setConstraints(networkConstraints())
            .addTag(TAG_TRANSFER)
            .build()
        transferRepository.setWorkId(id, request.id.toString())
        WorkManager.getInstance(context).enqueue(request)
        return id
    }

    /** Cancel a single transfer by id (cancels its WorkManager work too). */
    suspend fun cancel(id: Long) {
        val entity = transferRepository.getEntity(id) ?: return
        entity.workId?.let { workId ->
            WorkManager.getInstance(context).cancelWorkById(java.util.UUID.fromString(workId))
        }
        transferRepository.cancel(id)
    }

    private fun networkConstraints(): Constraints =
        Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

    private fun queryUriMetadata(uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment ?: "upload"
        var size = 0L
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null
        )?.use { c ->
            if (c.moveToFirst()) {
                val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                if (nameIdx >= 0) c.getString(nameIdx)?.let { name = it }
                if (sizeIdx >= 0) size = c.getLong(sizeIdx)
            }
        }
        return name to size
    }

    private companion object {
        const val TAG_TRANSFER = "cloudshelf-transfer"
    }
}
