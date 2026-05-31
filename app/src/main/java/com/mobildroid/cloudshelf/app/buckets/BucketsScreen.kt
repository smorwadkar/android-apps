package com.mobildroid.cloudshelf.app.buckets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mobildroid.cloudshelf.app.core.ui.EmptyState
import com.mobildroid.cloudshelf.app.core.ui.SkeletonListRows
import com.mobildroid.cloudshelf.app.data.s3.BucketSummary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BucketsScreen(
    onBucketSelected: (bucket: String) -> Unit,
    onOpenTransfers: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: BucketsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Your buckets") },
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = onOpenTransfers) {
                        Icon(Icons.Outlined.SwapHoriz, contentDescription = "Transfers")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.isLoading && state.buckets.isEmpty() -> {
                    SkeletonListRows(count = 5)
                }
                state.errorMessage != null && state.buckets.isEmpty() -> {
                    EmptyState(
                        icon = Icons.Outlined.WifiOff,
                        title = "Couldn't load your buckets",
                        body = state.errorMessage,
                        actionLabel = "Retry",
                        onAction = viewModel::refresh
                    )
                }
                state.buckets.isEmpty() -> {
                    EmptyState(
                        icon = Icons.Outlined.CloudOff,
                        title = "No buckets to show",
                        body = "Your IAM policy needs s3:ListAllMyBuckets to see this list. " +
                            "If you've just created your first bucket, tap refresh."
                    )
                }
                else -> {
                    BucketList(buckets = state.buckets, onBucketSelected = onBucketSelected)
                }
            }
        }
    }
}

@Composable
private fun BucketList(
    buckets: List<BucketSummary>,
    onBucketSelected: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 16.dp,
            vertical = 12.dp
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(buckets, key = { it.name }) { bucket ->
            BucketCard(bucket = bucket, onClick = { onBucketSelected(bucket.name) })
        }
    }
}

@Composable
private fun BucketCard(bucket: BucketSummary, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(10.dp)
                )
            }
            Spacer(Modifier.size(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = bucket.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1
                )
                val region = bucket.region
                Text(
                    text = when (region) {
                        null -> "Resolving region…"
                        "?" -> "Region unknown"
                        else -> region
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
