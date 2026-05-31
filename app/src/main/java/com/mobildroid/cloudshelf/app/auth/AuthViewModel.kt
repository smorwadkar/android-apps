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
 *
 * Kept as `AuthViewModel` to preserve the file name from Phase 0.
 */
@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {

    data class UiState(
        val tab: Tab = Tab.Cognito,
        val cognitoEmail: String = "",
        val cognitoPassword: String = "",
        val iamAccessKeyId: String = "",
        val iamSecretAccessKey: String = "",
        val iamSessionToken: String = "",
        val iamRegion: String = "us-east-1",
        val isCognitoAvailable: Boolean = AmplifyInitializer.isConfigured,
        val isSubmitting: Boolean = false,
        val errorMessage: String? = null,
        val signedIn: Boolean = false
    ) {
        enum class Tab { Cognito, IamKey }

        val canSubmitCognito: Boolean
            get() = isCognitoAvailable && cognitoEmail.isNotBlank() && cognitoPassword.isNotBlank() && !isSubmitting

        val canSubmitIam: Boolean
            get() = iamAccessKeyId.isNotBlank() &&
                iamSecretAccessKey.isNotBlank() &&
                iamRegion.isNotBlank() &&
                !isSubmitting
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    fun onTabSelected(tab: UiState.Tab) = update { copy(tab = tab, errorMessage = null) }

    fun onCognitoEmailChanged(value: String) = update { copy(cognitoEmail = value, errorMessage = null) }
    fun onCognitoPasswordChanged(value: String) = update { copy(cognitoPassword = value, errorMessage = null) }

    fun onIamAccessKeyChanged(value: String) = update { copy(iamAccessKeyId = value, errorMessage = null) }
    fun onIamSecretChanged(value: String) = update { copy(iamSecretAccessKey = value, errorMessage = null) }
    fun onIamSessionTokenChanged(value: String) = update { copy(iamSessionToken = value, errorMessage = null) }
    fun onIamRegionChanged(value: String) = update { copy(iamRegion = value, errorMessage = null) }

    fun submitCognito() {
        val state = _uiState.value
        if (!state.canSubmitCognito) return
        _uiState.value = state.copy(isSubmitting = true, errorMessage = null)
        viewModelScope.launch {
            runCatching {
                authRepository.signInCognito(state.cognitoEmail.trim(), state.cognitoPassword)
            }.onSuccess {
                _uiState.value = _uiState.value.copy(isSubmitting = false, signedIn = true)
            }.onFailure { e ->
                Timber.w(e, "Cognito sign-in failed")
                _uiState.value = _uiState.value.copy(
                    isSubmitting = false,
                    errorMessage = e.localizedMessage ?: "Sign-in failed."
                )
            }
        }
    }

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
                _uiState.value = _uiState.value.copy(
                    isSubmitting = false,
                    errorMessage = e.localizedMessage ?: "Could not validate those keys."
                )
            }
        }
    }

    private inline fun update(transform: UiState.() -> UiState) {
        _uiState.value = _uiState.value.transform()
    }
}
