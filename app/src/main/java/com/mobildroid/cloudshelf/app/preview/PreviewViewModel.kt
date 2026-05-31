package com.mobildroid.cloudshelf.app.preview

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mobildroid.cloudshelf.app.data.s3.S3ErrorMapper
import com.mobildroid.cloudshelf.app.data.s3.S3Repository
import com.mobildroid.cloudshelf.app.transfer.TransferScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.net.URLDecoder
import javax.inject.Inject
import kotlin.time.Duration.Companion.hours

@HiltViewModel
class PreviewViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val s3Repository: S3Repository,
    private val transferScheduler: TransferScheduler,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    sealed interface PreviewState {
        /**
         * @param bytesLoaded Bytes loaded so far (0 while we're still resolving what to load).
         * @param totalBytes  Total size in bytes when known (0 = indeterminate; UI falls
         *                    back to a spinner).
         */
        data class Loading(
            val bytesLoaded: Long = 0L,
            val totalBytes: Long = 0L
        ) : PreviewState
        data class Image(val file: File) : PreviewState
        data class Pdf(val file: File) : PreviewState
        data class Video(val url: String) : PreviewState
        data class Text(val content: String, val truncated: Boolean) : PreviewState
        data class Unsupported(val mimeType: String) : PreviewState
        data class Error(val message: String) : PreviewState
    }

    private val bucket: String =
        savedStateHandle.get<String>("bucket").orEmpty()
    private val key: String =
        URLDecoder.decode(savedStateHandle.get<String>("key").orEmpty(), "UTF-8")

    val displayName: String = key.substringAfterLast('/').ifEmpty { key }
    val mimeType: String = PreviewMimeDetector.mimeTypeFor(key)

    private val _state = MutableStateFlow<PreviewState>(PreviewState.Loading())
    val state: StateFlow<PreviewState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.value = PreviewState.Loading()
        viewModelScope.launch {
            runCatching { resolvePreview() }
                .onSuccess { _state.value = it }
                .onFailure { e ->
                    Timber.w(e, "preview failed for $bucket/$key")
                    _state.value = PreviewState.Error(S3ErrorMapper.toUiMessage(e))
                }
        }
    }

    /**
     * Forward S3-byte-progress into [PreviewState.Loading] without thrashing
     * recomposition: only emit when the percent ticks by ≥1% (or we're done).
     * Uses an IntArray for the captured "last percent" so Kotlin doesn't warn
     * about the `var` being closed over.
     */
    private fun progressReporter(): (bytesDone: Long, totalBytes: Long) -> Unit {
        val lastPct = intArrayOf(-1)
        return { done, total ->
            val pct = if (total > 0) (done * 100 / total).toInt() else -1
            if (pct != lastPct[0] || total == 0L || done == total) {
                lastPct[0] = pct
                _state.value = PreviewState.Loading(done, total)
            }
        }
    }

    /** Schedule a regular download via the existing TransferScheduler. */
    fun downloadFullObject(onScheduled: () -> Unit) {
        viewModelScope.launch {
            runCatching {
                transferScheduler.enqueueDownload(bucket, key, knownSizeBytes = 0L)
            }.onSuccess { onScheduled() }
                .onFailure { Timber.w(it, "Could not schedule download") }
        }
    }

    /**
     * Generate a 1-hour pre-signed URL and pass it to [onReady]. Caller is
     * expected to fire the Android share sheet from there. Errors are reported
     * via [onError] with a UI-safe message.
     */
    fun shareLink(
        onReady: (url: String) -> Unit,
        onError: (message: String) -> Unit
    ) {
        viewModelScope.launch {
            runCatching {
                s3Repository.presignGet(bucket = bucket, key = key, ttl = 1.hours)
            }.onSuccess { onReady(it) }
                .onFailure { e ->
                    Timber.w(e, "shareLink failed")
                    onError(S3ErrorMapper.toUiMessage(e))
                }
        }
    }

    private suspend fun resolvePreview(): PreviewState =
        when (PreviewMimeDetector.kindFor(key)) {
            PreviewKind.IMAGE -> {
                val file = File(context.cacheDir, "preview/${safeName(key)}")
                if (!file.exists() || file.length() == 0L) {
                    s3Repository.downloadToCacheFile(
                        bucket = bucket,
                        key = key,
                        target = file,
                        onProgress = progressReporter()
                    )
                }
                PreviewState.Image(file)
            }
            PreviewKind.VIDEO -> PreviewState.Video(s3Repository.presignGet(bucket, key))
            PreviewKind.PDF -> {
                val file = File(context.cacheDir, "preview/${safeName(key)}")
                if (!file.exists() || file.length() == 0L) {
                    s3Repository.downloadToCacheFile(
                        bucket = bucket,
                        key = key,
                        target = file,
                        onProgress = progressReporter()
                    )
                }
                PreviewState.Pdf(file)
            }
            PreviewKind.TEXT -> {
                val bytes = s3Repository.fetchBytes(
                    bucket = bucket,
                    key = key,
                    maxBytes = TEXT_BYTE_LIMIT,
                    onProgress = progressReporter()
                )
                PreviewState.Text(
                    content = bytes.toString(Charsets.UTF_8),
                    truncated = bytes.size >= TEXT_BYTE_LIMIT
                )
            }
            PreviewKind.UNSUPPORTED -> PreviewState.Unsupported(mimeType)
        }

    /** Make a safe local filename from an S3 key (strip slashes). */
    private fun safeName(key: String): String =
        key.replace('/', '_').take(120)

    private companion object {
        const val TEXT_BYTE_LIMIT = 256 * 1024  // 256 KB cap for text preview
    }
}
