package com.mobildroid.cloudshelf.app.transfer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TransfersViewModel @Inject constructor(
    private val transferRepository: TransferRepository,
    private val scheduler: TransferScheduler
) : ViewModel() {

    data class UiState(
        val active: List<Transfer> = emptyList(),
        val recent: List<Transfer> = emptyList()
    )

    val state: StateFlow<UiState> = transferRepository.observeAll()
        .map { all ->
            UiState(
                active = all.filter { it.isActive },
                recent = all.filter { !it.isActive }
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    fun cancel(id: Long) = viewModelScope.launch { scheduler.cancel(id) }
    fun delete(id: Long) = viewModelScope.launch { transferRepository.delete(id) }
    fun clearFinished() = viewModelScope.launch { transferRepository.clearFinished() }
}
