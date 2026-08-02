package com.mobildroid.cloudshelf.app.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Drives [SignInScreen]. Holds form state, kicks off sign-in via [AuthRepository],
 * surfaces loading and error states.
 */
@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {

    data class UiState(
        val iamAccessKeyId: String = "",
        val iamSecretAccessKey: String = "",
        val iamSessionToken: String = "",
        val iamRegion: String = "us-east-1",
        val isSubmitting: Boolean = false,
        val errorMessage: String? = null,
        val signedIn: Boolean = false
    ) {
        val canSubmitIam: Boolean
            get() = iamAccessKeyId.isNotBlank() &&
                iamSecretAccessKey.isNotBlank() &&
                iamRegion.isNotBlank() &&
                !isSubmitting
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    fun onIamAccessKeyChanged(value: String) { _uiState.value = _uiState.value.copy(iamAccessKeyId = value, errorMessage = null) }
    fun onIamSecretChanged(value: String) { _uiState.value = _uiState.value.copy(iamSecretAccessKey = value, errorMessage = null) }
    fun onIamSessionTokenChanged(value: String) { _uiState.value = _uiState.value.copy(iamSessionToken = value, errorMessage = null) }
    fun onIamRegionChanged(value: String) { _uiState.value = _uiState.value.copy(iamRegion = value, errorMessage = null) }

    fun submitIamKey() {
        val state = _uiState.value
        if (!state.canSubmitIam) return
        _uiState.value = state.copy(isSubmitting = true, errorMessage = null)
        viewModelScope.launch {
            runCatching {
                authRepository.signInWithIamKey(
                    accessKeyId = state.iamAccessKeyId,
                    secretAccessKey = state.iamSecretAccessKey,
                    sessionToken = state.iamSessionToken.ifBlank { null },
                    region = state.iamRegion
                )
            }.onSuccess {
                _uiState.value = _uiState.value.copy(isSubmitting = false, signedIn = true)
            }.onFailure { e ->
                Timber.w(e, "IAM-key sign-in failed")
                val message = when (e) {
                    is IamValidationTimeoutException -> e.message
                        ?: "Timed out contacting AWS. Check your internet connection and try again."
                    else -> e.localizedMessage ?: "Could not validate those keys."
                }
                _uiState.value = _uiState.value.copy(
                    isSubmitting = false,
                    errorMessage = message
                )
            }
        }
    }
}
