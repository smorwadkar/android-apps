package com.mobildroid.cloudshelf.app.transfer

import com.mobildroid.cloudshelf.app.data.db.transfer.TransferDao
import com.mobildroid.cloudshelf.app.data.db.transfer.TransferEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin wrapper over [TransferDao] that exposes domain types ([Transfer])
 * and lifecycle methods. Used by both Workers and the UI.
 */
@Singleton
class TransferRepository @Inject constructor(
    private val dao: TransferDao
) {

    fun observeAll(): Flow<List<Transfer>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    suspend fun enqueue(
        type: TransferType,
        bucket: String,
        objectKey: String,
        localUri: String,
        displayName: String,
        sizeBytes: Long
    ): Long {
        val now = System.currentTimeMillis()
        return dao.insert(
            TransferEntity(
                type = type.name,
                bucket = bucket,
                objectKey = objectKey,
                localUri = localUri,
                displayName = displayName,
                sizeBytes = sizeBytes,
                status = TransferStatus.QUEUED.name,
                createdAtEpochMs = now,
                updatedAtEpochMs = now
            )
        )
    }

    suspend fun get(id: Long): Transfer? = dao.getById(id)?.toDomain()
    suspend fun getEntity(id: Long): TransferEntity? = dao.getById(id)

    suspend fun setWorkId(id: Long, workId: String) =
        dao.updateWorkId(id, workId, System.currentTimeMillis())

    suspend fun setRunning(id: Long) =
        dao.updateStatus(id, TransferStatus.RUNNING.name, null, System.currentTimeMillis())

    suspend fun updateProgress(id: Long, bytes: Long) =
        dao.updateProgress(id, bytes, System.currentTimeMillis())

    suspend fun setUploadId(id: Long, uploadId: String?) =
        dao.updateUploadId(id, uploadId, System.currentTimeMillis())

    suspend fun setLocalUri(id: Long, uri: String) =
        dao.updateLocalUri(id, uri, System.currentTimeMillis())

    suspend fun complete(id: Long) =
        dao.updateStatus(id, TransferStatus.COMPLETED.name, null, System.currentTimeMillis())

    suspend fun fail(id: Long, message: String) =
        dao.updateStatus(id, TransferStatus.FAILED.name, message, System.currentTimeMillis())

    suspend fun cancel(id: Long) =
        dao.updateStatus(id, TransferStatus.CANCELLED.name, null, System.currentTimeMillis())

    suspend fun delete(id: Long) = dao.delete(id)
    suspend fun clearFinished() = dao.clearFinished()
}
