package com.mobildroid.cloudshelf.app.data.db.transfer

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One row per upload or download in flight (or recently completed).
 *
 * Multipart-upload resume relies on [uploadId] surviving a process restart —
 * if we restart and find a non-null uploadId, we call ListParts and skip
 * already-uploaded parts.
 */
@Entity(tableName = "transfers")
data class TransferEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,                      // "UPLOAD" or "DOWNLOAD"
    val bucket: String,
    val objectKey: String,
    val localUri: String,                  // SAF URI for uploads, file:// for downloads
    val displayName: String,
    val sizeBytes: Long,                   // total bytes (0 = unknown)
    val bytesTransferred: Long = 0,
    val status: String,                    // "QUEUED" / "RUNNING" / "COMPLETED" / "FAILED" / "CANCELLED"
    val uploadId: String? = null,          // multipart upload id (for resume)
    val workId: String? = null,            // WorkManager UUID
    val errorMessage: String? = null,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long
)
