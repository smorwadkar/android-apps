package com.mobildroid.cloudshelf.app.auth

import android.content.Context
import com.amplifyframework.AmplifyException
import com.amplifyframework.auth.cognito.AWSCognitoAuthPlugin
import com.amplifyframework.core.Amplify
import timber.log.Timber

/**
 * Best-effort Amplify (Cognito) initialization.
 *
 * Amplify is initialized only if `res/raw/amplifyconfiguration.json` exists.
 * Until you run `amplify init && amplify add auth`, that file is absent and
 * Cognito sign-in is gracefully disabled in the UI (the IAM-key path still works).
 */
object AmplifyInitializer {

    /** Set to true once configure() succeeded — checked by the SignInViewModel. */
    @Volatile
    var isConfigured: Boolean = false
        private set

    fun tryInitialize(context: Context) {
        if (isConfigured) return
        try {
            Amplify.addPlugin(AWSCognitoAuthPlugin())
            Amplify.configure(context.applicationContext)
            isConfigured = true
            Timber.i("Amplify configured successfully.")
        } catch (e: AmplifyException) {
            // Most common cause: amplifyconfiguration.json missing because
            // the user hasn't run `amplify add auth` yet. That's expected
            // pre-Cognito-setup; we just stay in IAM-key-only mode.
            Timber.w(e, "Amplify not configured — Cognito sign-in disabled. Run `amplify init && amplify add auth` to enable.")
        } catch (e: Exception) {
            Timber.e(e, "Unexpected Amplify init failure.")
        }
    }
}
