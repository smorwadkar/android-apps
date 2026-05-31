package com.mobildroid.cloudshelf.app.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for the user's auth state.
 *
 *  - Restores persisted IAM keys from disk on construction.
 *  - Exposes a [StateFlow] navigation can observe to gate the SignIn screen.
 *
 * UI never touches IamKeyStore directly — it goes through here.
 */
@Singleton
class AuthRepository @Inject constructor(
    private val iamKeyStore: IamKeyStore,
    private val stsValidator: StsValidator
) {

    sealed interface AuthState {
        data object Unknown : AuthState           // Initial — still checking persisted state.
        data object SignedOut : AuthState
        data class SignedIn(val credentials: CloudShelfCredentials) : AuthState
    }

    private val _state = MutableStateFlow<AuthState>(AuthState.Unknown)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    /**
     * Called once on app start (from [com.mobildroid.cloudshelf.app.MainActivity]'s
     * initialization side-effect) to decide where to land.
     */
    suspend fun restorePersistedSession() {
        val stored = iamKeyStore.load()
        _state.value = if (stored != null) AuthState.SignedIn(stored) else AuthState.SignedOut
    }

    /**
     * Sign in by validating and storing raw IAM keys.
     */
    suspend fun signInWithIamKey(
        accessKeyId: String,
        secretAccessKey: String,
        sessionToken: String?,
        region: String
    ) {
        val validated = stsValidator.validate(
            accessKeyId = accessKeyId.trim(),
            secretAccessKey = secretAccessKey.trim(),
            sessionToken = sessionToken?.trim()?.ifBlank { null },
            region = region.trim()
        )
        iamKeyStore.save(validated)
        _state.value = AuthState.SignedIn(validated)
    }

    suspend fun signOut() {
        iamKeyStore.clear()
        _state.value = AuthState.SignedOut
    }
}
