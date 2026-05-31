package com.mobildroid.cloudshelf.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.mobildroid.cloudshelf.app.auth.AmplifyInitializer
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import javax.inject.Inject

/**
 * Application entry point.
 *
 *  - Hilt is bootstrapped here via [HiltAndroidApp].
 *  - WorkManager uses a Hilt-aware [HiltWorkerFactory] so our Workers can inject
 *    repositories (S3Repository, etc.).
 *  - Amplify (Cognito) is intentionally NOT initialized here yet. Phase 1 will
 *    add `Amplify.addPlugin(AWSCognitoAuthPlugin())` + `Amplify.configure(this)`
 *    after `amplify init` produces `amplifyconfiguration.json`.
 */
@HiltAndroidApp
class CloudShelfApplication : Application(), Configuration.Provider, SingletonImageLoader.Factory {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        Timber.i("CloudShelf starting up (flavor=%s, version=%s)", BuildConfig.FLAVOR, BuildConfig.VERSION_NAME)

        // Best-effort: enables the Cognito sign-in tab if amplifyconfiguration.json is present.
        AmplifyInitializer.tryInitialize(this)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) android.util.Log.DEBUG else android.util.Log.INFO)
            .build()

    /**
     * Coil 3 doesn't ship with a network fetcher by default; we wire up the
     * OkHttp engine so `AsyncImage` can load HTTPS URLs (used by Phase 4 image
     * preview with pre-signed S3 GET URLs).
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory()) }
            .build()
}
