package com.mobildroid.cloudshelf.app.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mobildroid.cloudshelf.app.core.preferences.ThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onSignedOut: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.signedOut) {
        if (state.signedOut) onSignedOut()
    }
    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            AccountSection(
                identityLabel = state.identityLabel,
                identityDetail = state.identityDetail,
                isSigningOut = state.isSigningOut,
                onSignOut = viewModel::signOut
            )
            AppearanceSection(
                themeMode = state.themeMode,
                onThemeModeSelected = viewModel::setThemeMode
            )
            StorageSection(
                onClearPreviewCache = viewModel::clearPreviewCache,
                onClearFinishedTransfers = viewModel::clearFinishedTransfers
            )
            AboutSection(
                versionName = viewModel.versionName,
                packageName = viewModel.applicationId
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall)
}

@Composable
private fun AccountSection(
    identityLabel: String,
    identityDetail: String?,
    isSigningOut: Boolean,
    onSignOut: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("Account")
        Text("Signed in via $identityLabel")
        identityDetail?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(
            onClick = onSignOut,
            enabled = !isSigningOut
        ) {
            if (isSigningOut) CircularProgressIndicator(strokeWidth = 2.dp) else Text("Sign out")
        }
    }
}

@Composable
private fun AppearanceSection(
    themeMode: ThemeMode,
    onThemeModeSelected: (ThemeMode) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionHeader("Appearance")
        ThemeOption("Match system", ThemeMode.SYSTEM, themeMode, onThemeModeSelected)
        ThemeOption("Light", ThemeMode.LIGHT, themeMode, onThemeModeSelected)
        ThemeOption("Dark", ThemeMode.DARK, themeMode, onThemeModeSelected)
        ThemeOption("Vibe ✨", ThemeMode.VIBE, themeMode, onThemeModeSelected)
    }
}

@Composable
private fun ThemeOption(
    label: String,
    value: ThemeMode,
    selected: ThemeMode,
    onSelected: (ThemeMode) -> Unit
) {
    val isSelected = selected == value
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = isSelected,
                onClick = { onSelected(value) },
                role = Role.RadioButton
            )
            .padding(vertical = 4.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        RadioButton(selected = isSelected, onClick = null)
        Text(label, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun StorageSection(
    onClearPreviewCache: () -> Unit,
    onClearFinishedTransfers: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("Storage")
        OutlinedButton(onClick = onClearPreviewCache, modifier = Modifier.fillMaxWidth()) {
            Text("Clear preview cache")
        }
        OutlinedButton(onClick = onClearFinishedTransfers, modifier = Modifier.fillMaxWidth()) {
            Text("Clear finished transfers")
        }
    }
}

@Composable
private fun AboutSection(versionName: String, packageName: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionHeader("About")
        Text("CloudShelf $versionName", style = MaterialTheme.typography.bodyLarge)
        Text(packageName, style = MaterialTheme.typography.bodySmall)
    }
}
