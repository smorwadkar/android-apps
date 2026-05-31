package com.mobildroid.cloudshelf.app.auth

import com.amplifyframework.auth.cognito.AWSCognitoAuthSession
import com.amplifyframework.auth.options.AuthSignOutOptions
import com.amplifyframework.kotlin.core.Amplify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for the user's auth state.
 *
 *  - Restores from disk on construction (IAM-key path) or from Amplify (Cognito).
 *  - Exposes a [StateFlow] navigation can observe to gate the SignIn screen.
 *  - Knows how to sign in and sign out via either path.
 *
 * UI never touches Amplify or IamKeyStore directly — it goes through here.
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
        // 1) Prefer a live Cognito session if Amplify is configured.
        if (AmplifyInitializer.isConfigured) {
            try {
                val session = Amplify.Auth.fetchAuthSession() as AWSCognitoAuthSession
                if (session.isSignedIn) {
                    val email = runCatching {
                        Amplify.Auth.getCurrentUser().username
                    }.getOrNull()
                    val region = session.awsCredentialsResult.value
                        ?.let { extractRegionFromSession() }
                        ?: DEFAULT_REGION
                    _state.value = AuthState.SignedIn(
                        CloudShelfCredentials.Cognito(
                            identityId = session.identityIdResult.value ?: "unknown",
                            email = email,
                            defaultRegion = region
                        )
                    )
                    return
                }
            } catch (e: Exception) {
                Timber.w(e, "Cognito session restore failed; falling back to IAM-key check.")
            }
        }

        // 2) Otherwise see if we have IAM keys on disk.
        val stored = iamKeyStore.load()
        _state.value = if (stored != null) AuthState.SignedIn(stored) else AuthState.SignedOut
    }

    /**
     * Sign in via Cognito (Amplify). Throws on failure — caller surfaces UI error copy.
     */
    suspend fun signInCognito(email: String, password: String) {
        check(AmplifyInitializer.isConfigured) {
            "Amplify is not configured — run `amplify init && amplify add auth` first."
        }
        val result = Amplify.Auth.signIn(email, password)
        if (!result.isSignedIn) {
            // Could be a multi-step flow (MFA, confirm signup). Phase 1 surfaces the
            // step name in the error message; multi-factor UI is a v1.1 follow-up.
            error("Sign-in requires another step: ${result.nextStep.signInStep}")
        }
        // Reuse the persisted-session restore path so we end up in the same state.
        restorePersistedSession()
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
        // Clear both paths regardless of which one we used — simplest invariant.
        iamKeyStore.clear()
        if (AmplifyInitializer.isConfigured) {
            try {
                Amplify.Auth.signOut(AuthSignOutOptions.builder().globalSignOut(false).build())
            } catch (e: Exception) {
                Timber.w(e, "Cognito sign-out failed (continuing).")
            }
        }
        _state.value = AuthState.SignedOut
    }

    /**
     * Region used when we don't have a better signal. Cognito Identity Pools
     * are region-bound; Phase 2's GetBucketLocation overrides per bucket anyway.
     */
    private fun extractRegionFromSession(): String = DEFAULT_REGION

    private companion object {
        const val DEFAULT_REGION = "us-east-1"
    }
}
