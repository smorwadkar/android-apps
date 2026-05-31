package com.mobildroid.cloudshelf.app.transfer

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.mobildroid.cloudshelf.app.preview.PreviewMimeDetector
import java.io.File
import java.io.OutputStream

/**
 * Where downloaded bytes go.
 *
 *  - Android 10+ (API 29+): MediaStore. Files appear in Gallery (images and
 *    videos) or the Files app under Download/CloudShelf. No runtime
 *    permission is needed because each app owns rows it inserts.
 *  - Android 8 / 9 (API 26 – 28): app-private cache. Files don't appear in
 *    Gallery / Files unless the user shares them out. This is acceptable for
 *    older devices; Phase 6 polish will add a SAF tree-URI override.
 *
 * The destination URI is persisted to [TransferEntity.localUri] so that a
 * killed-and-retried worker can resume into the same row instead of creating
 * a new one (which would orphan the partial bytes in MediaStore).
 */
object DownloadDestination {

    /** Create a fresh destination and return its URI as a string. */
    fun create(context: Context, displayName: String, objectKey: String): String {
        val mime = PreviewMimeDetector.mimeTypeFor(objectKey)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            createMediaStoreEntry(context, displayName, mime).toString()
        } else {
            val file = File(context.cacheDir, "downloads/$displayName")
            file.parentFile?.mkdirs()
            Uri.fromFile(file).toString()
        }
    }

    /** Open an output stream into the destination. Use mode "wa" to append, "w" to truncate. */
    fun openOutputStream(context: Context, uriString: String, mode: String): OutputStream {
        val uri = Uri.parse(uriString)
        return when (uri.scheme) {
            "content" -> context.contentResolver.openOutputStream(uri, mode)
                ?: error("Could not open content output stream for $uri")
            "file" -> {
                val file = File(requireNotNull(uri.path) { "file:// URI without path" })
                file.parentFile?.mkdirs()
                java.io.FileOutputStream(file, /* append = */ mode == "wa")
            }
            else -> error("Unsupported destination scheme: ${uri.scheme}")
        }
    }

    /** Current size of the destination in bytes (0 if it doesn't exist yet). */
    fun currentSize(context: Context, uriString: String): Long {
        val uri = Uri.parse(uriString)
        return when (uri.scheme) {
            "content" -> runCatching {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
            }.getOrDefault(0L)
            "file" -> uri.path?.let { File(it).length() } ?: 0L
            else -> 0L
        }
    }

    /**
     * Mark a MediaStore entry as no longer pending so Gallery / Files can see it.
     * No-op for file:// destinations.
     */
    fun finalize(context: Context, uriString: String) {
        val uri = Uri.parse(uriString)
        if (uri.scheme == "content" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            context.contentResolver.update(uri, values, null, null)
        }
    }

    /** Best-effort delete on cancel / abandon. */
    fun delete(context: Context, uriString: String) {
        val uri = Uri.parse(uriString)
        runCatching {
            when (uri.scheme) {
                "content" -> context.contentResolver.delete(uri, null, null)
                "file" -> uri.path?.let { File(it).delete() }
                else -> Unit
            }
        }
    }

    private fun createMediaStoreEntry(context: Context, displayName: String, mime: String): Uri {
        val (collection, relativePath) = when {
            mime.startsWith("image/") -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI to "${android.os.Environment.DIRECTORY_PICTURES}/$APP_FOLDER"
            mime.startsWith("video/") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI to "${android.os.Environment.DIRECTORY_MOVIES}/$APP_FOLDER"
            mime.startsWith("audio/") -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to "${android.os.Environment.DIRECTORY_MUSIC}/$APP_FOLDER"
            else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI to "${android.os.Environment.DIRECTORY_DOWNLOADS}/$APP_FOLDER"
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return context.contentResolver.insert(collection, values)
            ?: error("Could not create MediaStore entry for $displayName")
    }

    private const val APP_FOLDER = "CloudShelf"
}
