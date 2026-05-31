package com.mobildroid.cloudshelf.app.browser

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mobildroid.cloudshelf.app.data.s3.S3ErrorMapper
import com.mobildroid.cloudshelf.app.data.s3.S3Item
import com.mobildroid.cloudshelf.app.data.s3.S3Repository
import com.mobildroid.cloudshelf.app.transfer.TransferScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class BrowserViewModel @Inject constructor(
    private val s3Repository: S3Repository,
    private val transferScheduler: TransferScheduler,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    data class UiState(
        val bucket: String = "",
        val prefix: String = "",
        val items: List<S3Item> = emptyList(),
        val isLoading: Boolean = true,
        val isLoadingMore: Boolean = false,
        val continuationToken: String? = null,
        val sort: Sort = Sort.NAME_ASC,
        val search: String = "",
        val errorMessage: String? = null,
        val dialog: Dialog? = null,
        val operationInFlight: Boolean = false
    ) {
        enum class Sort { NAME_ASC, NAME_DESC, MODIFIED_DESC, SIZE_DESC }

        sealed interface Dialog {
            data class ConfirmDelete(val item: S3Item) : Dialog
            data class Rename(val item: S3Item.Object, val initialName: String) : Dialog
            data object NewFolder : Dialog
        }

        /** Items after applying [search] and [sort]. Cheap on small pages. */
        val visibleItems: List<S3Item>
            get() {
                val filtered = if (search.isBlank()) items
                else items.filter { it.displayName.contains(search, ignoreCase = true) }
                return when (sort) {
                    Sort.NAME_ASC -> filtered.sortedBy { it.displayName.lowercase() }
                    Sort.NAME_DESC -> filtered.sortedByDescending { it.displayName.lowercase() }
                    Sort.MODIFIED_DESC -> filtered.sortedByDescending {
                        (it as? S3Item.Object)?.lastModifiedEpochMs ?: Long.MAX_VALUE
                    }
                    Sort.SIZE_DESC -> filtered.sortedByDescending {
                        (it as? S3Item.Object)?.sizeBytes ?: Long.MAX_VALUE
                    }
                }
            }

        /**
         * Breadcrumb segments derived from [prefix]. Each tuple is
         * (label, full-prefix-to-navigate-to).
         */
        val breadcrumbs: List<Pair<String, String>>
            get() {
                val parts = prefix.trim('/').split('/').filter { it.isNotEmpty() }
                val crumbs = mutableListOf<Pair<String, String>>()
                var acc = ""
                parts.forEach { part ->
                    acc = if (acc.isEmpty()) "$part/" else "$acc$part/"
                    crumbs += part to acc
                }
                return crumbs
            }
    }

    private val _state = MutableStateFlow(
        UiState(
            bucket = savedStateHandle.get<String>("bucket").orEmpty(),
            prefix = savedStateHandle.get<String>("prefix").orEmpty()
        )
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    /**
     * One-shot UI messages (snackbar text). Distinct from [state] so a message
     * doesn't replay on configuration change.
     */
    private val _messages = MutableSharedFlow<String>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    init {
        loadFirstPage()
    }

    fun refresh() = loadFirstPage()

    fun loadFirstPage() {
        val s = _state.value
        _state.update { it.copy(isLoading = true, errorMessage = null, items = emptyList(), continuationToken = null) }
        viewModelScope.launch {
            runCatching { s3Repository.listObjects(s.bucket, s.prefix) }
                .onSuccess { page ->
                    _state.update {
                        it.copy(
                            isLoading = false,
                            items = page.items,
                            continuationToken = page.nextContinuationToken
                        )
                    }
                }
                .onFailure { e ->
                    Timber.w(e, "listObjects failed (bucket=${s.bucket} prefix=${s.prefix})")
                    _state.update {
                        it.copy(isLoading = false, errorMessage = S3ErrorMapper.toUiMessage(e))
                    }
                }
        }
    }

    fun loadMore() {
        val s = _state.value
        val token = s.continuationToken ?: return
        if (s.isLoadingMore) return
        _state.update { it.copy(isLoadingMore = true) }
        viewModelScope.launch {
            runCatching { s3Repository.listObjects(s.bucket, s.prefix, token) }
                .onSuccess { page ->
                    _state.update {
                        it.copy(
                            isLoadingMore = false,
                            items = it.items + page.items,
                            continuationToken = page.nextContinuationToken
                        )
                    }
                }
                .onFailure { e ->
                    Timber.w(e, "listObjects (loadMore) failed")
                    _state.update {
                        it.copy(isLoadingMore = false, errorMessage = S3ErrorMapper.toUiMessage(e))
                    }
                }
        }
    }

    fun onSortChanged(sort: UiState.Sort) = _state.update { it.copy(sort = sort) }
    fun onSearchChanged(value: String) = _state.update { it.copy(search = value) }

    /** Enqueue uploads of the user-picked URIs into the current bucket+prefix. */
    fun enqueueUploads(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val s = _state.value
        viewModelScope.launch {
            uris.forEach { uri ->
                runCatching {
                    transferScheduler.enqueueUpload(s.bucket, s.prefix, uri)
                }.onFailure { Timber.w(it, "enqueueUpload failed") }
            }
        }
    }

    /** Enqueue a download of an S3 object the user tapped. */
    fun enqueueDownload(obj: S3Item.Object) {
        val s = _state.value
        viewModelScope.launch {
            runCatching {
                transferScheduler.enqueueDownload(s.bucket, obj.key, obj.sizeBytes)
            }.onFailure { Timber.w(it, "enqueueDownload failed") }
        }
    }

    /**
     * Generate a short-lived presigned GET URL used for inline image
     * thumbnails in list rows. presignGet is pure local crypto so this is
     * cheap to call per row; Coil caches the resulting fetch.
     */
    suspend fun presignThumbnail(objectKey: String): String? = runCatching {
        s3Repository.presignGet(_state.value.bucket, objectKey)
    }.getOrNull()

    // ---- Phase 5 file ops -----------------------------------------------

    fun showDeleteDialog(item: S3Item) = _state.update {
        it.copy(dialog = UiState.Dialog.ConfirmDelete(item))
    }

    fun showRenameDialog(obj: S3Item.Object) = _state.update {
        it.copy(dialog = UiState.Dialog.Rename(obj, obj.displayName))
    }

    fun showNewFolderDialog() = _state.update {
        it.copy(dialog = UiState.Dialog.NewFolder)
    }

    fun dismissDialog() = _state.update { it.copy(dialog = null) }

    fun confirmDelete() {
        val dialog = _state.value.dialog as? UiState.Dialog.ConfirmDelete ?: return
        val bucket = _state.value.bucket
        _state.update { it.copy(operationInFlight = true) }
        viewModelScope.launch {
            runCatching {
                when (val item = dialog.item) {
                    is S3Item.Object -> s3Repository.deleteObject(bucket, item.key)
                    is S3Item.Folder -> s3Repository.deletePrefix(bucket, item.prefix)
                }
            }.onSuccess {
                _messages.tryEmit("Deleted ${dialog.item.displayName}")
                _state.update { it.copy(dialog = null, operationInFlight = false) }
                loadFirstPage()
            }.onFailure { e ->
                Timber.w(e, "delete failed")
                _state.update { it.copy(operationInFlight = false) }
                _messages.tryEmit(S3ErrorMapper.toUiMessage(e))
            }
        }
    }

    fun confirmRename(newName: String) {
        val dialog = _state.value.dialog as? UiState.Dialog.Rename ?: return
        val trimmed = newName.trim()
        if (trimmed.isEmpty() || trimmed == dialog.initialName) {
            _state.update { it.copy(dialog = null) }
            return
        }
        val s = _state.value
        val parentPrefix = dialog.item.key.substringBeforeLast('/', missingDelimiterValue = "")
        val destKey = if (parentPrefix.isEmpty()) trimmed else "$parentPrefix/$trimmed"
        _state.update { it.copy(operationInFlight = true) }
        viewModelScope.launch {
            runCatching { s3Repository.renameObject(s.bucket, dialog.item.key, destKey) }
                .onSuccess {
                    _messages.tryEmit("Renamed to $trimmed")
                    _state.update { it.copy(dialog = null, operationInFlight = false) }
                    loadFirstPage()
                }
                .onFailure { e ->
                    Timber.w(e, "rename failed")
                    _state.update { it.copy(operationInFlight = false) }
                    _messages.tryEmit(S3ErrorMapper.toUiMessage(e))
                }
        }
    }

    fun confirmCreateFolder(name: String) {
        val trimmed = name.trim().trim('/')
        if (trimmed.isEmpty()) {
            _state.update { it.copy(dialog = null) }
            return
        }
        val s = _state.value
        val newPrefix = if (s.prefix.isEmpty()) "$trimmed/" else "${s.prefix}$trimmed/"
        _state.update { it.copy(operationInFlight = true) }
        viewModelScope.launch {
            runCatching { s3Repository.createFolder(s.bucket, newPrefix) }
                .onSuccess {
                    _messages.tryEmit("Created folder $trimmed/")
                    _state.update { it.copy(dialog = null, operationInFlight = false) }
                    loadFirstPage()
                }
                .onFailure { e ->
                    Timber.w(e, "create folder failed")
                    _state.update { it.copy(operationInFlight = false) }
                    _messages.tryEmit(S3ErrorMapper.toUiMessage(e))
                }
        }
    }
}
