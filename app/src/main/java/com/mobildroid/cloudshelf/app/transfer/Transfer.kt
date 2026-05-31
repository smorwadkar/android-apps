package com.mobildroid.cloudshelf.app.transfer

import com.mobildroid.cloudshelf.app.data.db.transfer.TransferEntity

enum class TransferType { UPLOAD, DOWNLOAD }

enum class TransferStatus { QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED }

/**
 * UI-friendly view of a [TransferEntity].
 */
data class Transfer(
    val id: Long,
    val type: TransferType,
    val bucket: String,
    val objectKey: String,
    val displayName: String,
    val sizeBytes: Long,
    val bytesTransferred: Long,
    val status: TransferStatus,
    val errorMessage: String?
) {
    val progressFraction: Float
        get() = if (sizeBytes <= 0) 0f else (bytesTransferred.toFloat() / sizeBytes).coerceIn(0f, 1f)

    val isActive: Boolean
        get() = status == TransferStatus.QUEUED || status == TransferStatus.RUNNING
}

internal fun TransferEntity.toDomain(): Transfer = Transfer(
    id = id,
    type = TransferType.valueOf(type),
    bucket = bucket,
    objectKey = objectKey,
    displayName = displayName,
    sizeBytes = sizeBytes,
    bytesTransferred = bytesTransferred,
    status = TransferStatus.valueOf(status),
    errorMessage = errorMessage
)
