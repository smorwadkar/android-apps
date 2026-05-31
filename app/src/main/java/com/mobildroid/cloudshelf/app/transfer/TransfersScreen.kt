package com.mobildroid.cloudshelf.app.transfer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransfersScreen(
    onBack: () -> Unit,
    viewModel: TransfersViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Transfers") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (state.recent.isNotEmpty()) {
                        TextButton(onClick = viewModel::clearFinished) { Text("Clear") }
                    }
                }
            )
        }
    ) { padding ->
        if (state.active.isEmpty() && state.recent.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No transfers yet.")
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                if (state.active.isNotEmpty()) {
                    item { SectionHeader("Active") }
                    items(state.active, key = { it.id }) { t ->
                        TransferRow(
                            transfer = t,
                            onCancel = { viewModel.cancel(t.id) },
                            onDelete = null
                        )
                        HorizontalDivider()
                    }
                }
                if (state.recent.isNotEmpty()) {
                    item { SectionHeader("Recent") }
                    items(state.recent, key = { it.id }) { t ->
                        TransferRow(
                            transfer = t,
                            onCancel = null,
                            onDelete = { viewModel.delete(t.id) }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(label: String) {
    Text(
        text = label,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
private fun TransferRow(
    transfer: Transfer,
    onCancel: (() -> Unit)?,
    onDelete: (() -> Unit)?
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(transfer.displayName, maxLines = 1) },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${transfer.type.name.lowercase()} · ${transfer.bucket}/${transfer.objectKey}", maxLines = 1)
                    val statusLine = when (transfer.status) {
                        TransferStatus.QUEUED -> "Queued"
                        TransferStatus.RUNNING -> "${(transfer.progressFraction * 100).toInt()}%"
                        TransferStatus.COMPLETED -> "Completed"
                        TransferStatus.FAILED -> "Failed — ${transfer.errorMessage ?: "see logs"}"
                        TransferStatus.CANCELLED -> "Cancelled"
                    }
                    Text(statusLine)
                    if (transfer.isActive) {
                        if (transfer.sizeBytes > 0) {
                            LinearProgressIndicator(
                                progress = { transfer.progressFraction },
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            },
            trailingContent = {
                if (onCancel != null) {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Filled.Close, contentDescription = "Cancel")
                    }
                } else if (onDelete != null) {
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Filled.Delete, contentDescription = "Remove from history")
                    }
                }
            }
        )
    }
}
