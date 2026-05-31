package com.mobildroid.cloudshelf.app.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mobildroid.cloudshelf.app.auth.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Triggers session restore on first composition and exposes [AuthRepository.state]
 * so the NavHost can decide its start destination once we know the answer.
 */
@HiltViewModel
class AuthGateViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {

    val state: StateFlow<AuthRepository.AuthState> = authRepository.state

    init {
        viewModelScope.launch { authRepository.restorePersistedSession() }
    }
}
