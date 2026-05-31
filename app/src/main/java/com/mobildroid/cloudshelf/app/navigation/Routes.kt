package com.mobildroid.cloudshelf.app.navigation

/**
 * All in-app navigation destinations.
 *
 * Routes that take arguments use the standard Compose-Navigation `route/{arg}` pattern;
 * helpers like [browser] / [preview] build the actual URI to pass to `navController.navigate(...)`.
 */
object Routes {
    const val SIGN_IN = "signIn"
    const val BUCKETS = "buckets"

    // Browser opens at a specific bucket + optional prefix.
    const val BROWSER_ROUTE = "browser/{bucket}?prefix={prefix}"
    fun browser(bucket: String, prefix: String = ""): String =
        "browser/$bucket?prefix=${prefix}"

    // Preview opens a specific object key in a bucket.
    const val PREVIEW_ROUTE = "preview/{bucket}/{key}"
    fun preview(bucket: String, key: String): String =
        "preview/$bucket/${java.net.URLEncoder.encode(key, "UTF-8")}"

    const val TRANSFERS = "transfers"
    const val SETTINGS = "settings"
}
