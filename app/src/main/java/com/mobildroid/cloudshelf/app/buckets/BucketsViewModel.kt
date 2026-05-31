package com.mobildroid.cloudshelf.app.buckets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mobildroid.cloudshelf.app.data.s3.BucketSummary
import com.mobildroid.cloudshelf.app.data.s3.S3ErrorMapper
import com.mobildroid.cloudshelf.app.data.s3.S3Repository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class BucketsViewModel @Inject constructor(
    private val s3Repository: S3Repository
) : ViewModel() {

    data class UiState(
        val isLoading: Boolean = true,
        val buckets: List<BucketSummary> = emptyList(),
        val errorMessage: String? = null
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            runCatching { s3Repository.listBuckets() }
                .onSuccess { buckets ->
                    _state.update { it.copy(isLoading = false, buckets = buckets) }
                    // Fire off region resolution per bucket in the background.
                    // Each completion updates the row's badge.
                    buckets.forEach { row ->
                        if (row.region == null) launch { resolveRegion(row.name) }
                    }
                }
                .onFailure { e ->
                    Timber.w(e, "listBuckets failed")
                    _state.update {
                        it.copy(isLoading = false, errorMessage = S3ErrorMapper.toUiMessage(e))
                    }
                }
        }
    }

    private suspend fun resolveRegion(bucket: String) {
        runCatching { s3Repository.getBucketRegion(bucket) }
            .onSuccess { region ->
                _state.update { s ->
                    s.copy(buckets = s.buckets.map { b ->
                        if (b.name == bucket) b.copy(region = region) else b
                    })
                }
            }
            .onFailure { e ->
                Timber.w(e, "getBucketRegion failed for $bucket")
                // Mark unknown so the badge stops spinning forever.
                _state.update { s ->
                    s.copy(buckets = s.buckets.map { b ->
                        if (b.name == bucket) b.copy(region = "?") else b
                    })
                }
            }
    }
}
