package com.mobildroid.cloudshelf.app.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mobildroid.cloudshelf.app.auth.AuthRepository
import com.mobildroid.cloudshelf.app.auth.CloudShelfCredentials
import com.mobildroid.cloudshelf.app.core.preferences.ThemeMode
import com.mobildroid.cloudshelf.app.core.preferences.UserPreferences
import com.mobildroid.cloudshelf.app.transfer.TransferRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val authRepository: AuthRepository,
    private val userPreferences: UserPreferences,
    private val transferRepository: TransferRepository
) : ViewModel() {

    data class UiState(
        val identityLabel: String = "Loading…",
        val identityDetail: String? = null,
        val themeMode: ThemeMode = ThemeMode.SYSTEM,
        val isSigningOut: Boolean = false,
        val signedOut: Boolean = false
    )

    val versionName: String = com.mobildroid.cloudshelf.app.BuildConfig.VERSION_NAME
    val applicationId: String = com.mobildroid.cloudshelf.app.BuildConfig.APPLICATION_ID

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    init {
        // Identity (Cognito / IAM) → UiState
        viewModelScope.launch {
            authRepository.state.collect { auth ->
                val (label, detail) = when (auth) {
                    is AuthRepository.AuthState.SignedIn -> when (val c = auth.credentials) {
                        is CloudShelfCredentials.Cognito -> "Cognito" to (c.email ?: c.identityId)
                        is CloudShelfCredentials.IamKey -> "IAM key" to (c.arn ?: c.accessKeyId)
                    }
                    else -> "Signed out" to null
                }
                _state.update { it.copy(identityLabel = label, identityDetail = detail) }
            }
        }
        // Theme mode flow → UiState
        viewModelScope.launch {
            userPreferences.themeMode.collect { mode ->
                _state.update { it.copy(themeMode = mode) }
            }
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { userPreferences.setThemeMode(mode) }
    }

    fun signOut() {
        _state.update { it.copy(isSigningOut = true) }
        viewModelScope.launch {
            runCatching { authRepository.signOut() }
            runCatching { withContext(Dispatchers.IO) { previewDir().deleteRecursively() } }
            _state.update { it.copy(isSigningOut = false, signedOut = true) }
        }
    }

    fun clearPreviewCache() = viewModelScope.launch {
        val ok = withContext(Dispatchers.IO) {
            runCatching { previewDir().deleteRecursively() }.getOrDefault(false)
        }
        Timber.i("Preview cache cleared: $ok")
        _messages.tryEmit(if (ok) "Preview cache cleared." else "Couldn't clear cache.")
    }

    fun clearFinishedTransfers() = viewModelScope.launch {
        runCatching { transferRepository.clearFinished() }
        _messages.tryEmit("Cleared finished transfers.")
    }

    private fun previewDir(): File = File(context.cacheDir, "preview")
}
