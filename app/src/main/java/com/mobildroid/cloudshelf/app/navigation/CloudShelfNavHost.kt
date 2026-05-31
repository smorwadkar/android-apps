package com.mobildroid.cloudshelf.app.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.mobildroid.cloudshelf.app.auth.AuthRepository
import com.mobildroid.cloudshelf.app.auth.SignInScreen
import com.mobildroid.cloudshelf.app.browser.BrowserScreen
import com.mobildroid.cloudshelf.app.buckets.BucketsScreen
import com.mobildroid.cloudshelf.app.preview.PreviewScreen
import com.mobildroid.cloudshelf.app.settings.SettingsScreen
import com.mobildroid.cloudshelf.app.transfer.TransfersScreen

@Composable
fun CloudShelfNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    authGate: AuthGateViewModel = hiltViewModel()
) {
    val authState by authGate.state.collectAsStateWithLifecycle()

    // While we're still checking persisted sessions, show a spinner instead of
    // flashing the SignIn screen for users who are already signed in.
    if (authState is AuthRepository.AuthState.Unknown) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val startDestination = if (authState is AuthRepository.AuthState.SignedIn) {
        Routes.BUCKETS
    } else {
        Routes.SIGN_IN
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier
    ) {
        composable(Routes.SIGN_IN) {
            SignInScreen(
                onSignedIn = {
                    navController.navigate(Routes.BUCKETS) {
                        popUpTo(Routes.SIGN_IN) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.BUCKETS) {
            BucketsScreen(
                onBucketSelected = { bucket ->
                    navController.navigate(Routes.browser(bucket))
                },
                onOpenTransfers = { navController.navigate(Routes.TRANSFERS) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) }
            )
        }

        composable(
            route = Routes.BROWSER_ROUTE,
            arguments = listOf(
                navArgument("bucket") { type = NavType.StringType },
                navArgument("prefix") {
                    type = NavType.StringType
                    defaultValue = ""
                }
            )
        ) { entry ->
            val bucket = entry.arguments?.getString("bucket").orEmpty()
            val prefix = entry.arguments?.getString("prefix").orEmpty()
            BrowserScreen(
                bucket = bucket,
                prefix = prefix,
                onNavigateToPrefix = { newPrefix ->
                    navController.navigate(Routes.browser(bucket, newPrefix))
                },
                onOpenFile = { key ->
                    navController.navigate(Routes.preview(bucket, key))
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Routes.PREVIEW_ROUTE,
            arguments = listOf(
                navArgument("bucket") { type = NavType.StringType },
                navArgument("key") { type = NavType.StringType }
            )
        ) { entry ->
            val bucket = entry.arguments?.getString("bucket").orEmpty()
            val rawKey = entry.arguments?.getString("key").orEmpty()
            val key = java.net.URLDecoder.decode(rawKey, "UTF-8")
            PreviewScreen(
                bucket = bucket,
                key = key,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.TRANSFERS) {
            TransfersScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onSignedOut = {
                    navController.navigate(Routes.SIGN_IN) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }
    }
}
