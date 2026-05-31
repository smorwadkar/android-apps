package com.mobildroid.cloudshelf.app.data.db.transfer

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TransferDao {

    @Insert
    suspend fun insert(entity: TransferEntity): Long

    @Update
    suspend fun update(entity: TransferEntity)

    @Query("SELECT * FROM transfers WHERE id = :id")
    suspend fun getById(id: Long): TransferEntity?

    @Query("SELECT * FROM transfers ORDER BY updatedAtEpochMs DESC")
    fun observeAll(): Flow<List<TransferEntity>>

    @Query("UPDATE transfers SET bytesTransferred = :bytes, updatedAtEpochMs = :now WHERE id = :id")
    suspend fun updateProgress(id: Long, bytes: Long, now: Long)

    @Query("UPDATE transfers SET status = :status, errorMessage = :error, updatedAtEpochMs = :now WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String, error: String?, now: Long)

    @Query("UPDATE transfers SET uploadId = :uploadId, updatedAtEpochMs = :now WHERE id = :id")
    suspend fun updateUploadId(id: Long, uploadId: String?, now: Long)

    @Query("UPDATE transfers SET workId = :workId, updatedAtEpochMs = :now WHERE id = :id")
    suspend fun updateWorkId(id: Long, workId: String, now: Long)

    @Query("UPDATE transfers SET localUri = :uri, updatedAtEpochMs = :now WHERE id = :id")
    suspend fun updateLocalUri(id: Long, uri: String, now: Long)

    @Query("DELETE FROM transfers WHERE status IN ('COMPLETED', 'FAILED', 'CANCELLED')")
    suspend fun clearFinished()

    @Query("DELETE FROM transfers WHERE id = :id")
    suspend fun delete(id: Long)
}
