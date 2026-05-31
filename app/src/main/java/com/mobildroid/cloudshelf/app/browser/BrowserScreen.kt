package com.mobildroid.cloudshelf.app.browser

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.VideoFile
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.mobildroid.cloudshelf.app.core.ui.EmptyState
import com.mobildroid.cloudshelf.app.core.ui.SkeletonListRows
import com.mobildroid.cloudshelf.app.data.s3.S3Item
import com.mobildroid.cloudshelf.app.preview.PreviewKind
import com.mobildroid.cloudshelf.app.preview.PreviewMimeDetector
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    bucket: String,
    prefix: String,
    onNavigateToPrefix: (String) -> Unit,
    onOpenFile: (key: String) -> Unit,
    onBack: () -> Unit,
    viewModel: BrowserViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* result intentionally ignored — transfers still work without it. */ }

    val uploadPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.enqueueUploads(uris)
            scope.launch {
                snackbarHostState.showSnackbar("${uris.size} upload(s) scheduled")
            }
        }
    }

    fun startUpload() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        uploadPicker.launch(arrayOf("*/*"))
    }

    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val total = listState.layoutInfo.totalItemsCount
            state.continuationToken != null && total > 0 && lastVisible >= total - 4
        }
    }
    LaunchedEffect(shouldLoadMore) { if (shouldLoadMore) viewModel.loadMore() }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { msg -> snackbarHostState.showSnackbar(msg) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(bucket, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::showNewFolderDialog) {
                        Icon(Icons.Filled.CreateNewFolder, contentDescription = "New folder")
                    }
                    SortMenu(state.sort, viewModel::onSortChanged)
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = ::startUpload,
                icon = { Icon(Icons.Filled.Upload, contentDescription = null) },
                text = { Text("Upload") }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            BreadcrumbRow(
                bucket = bucket,
                breadcrumbs = state.breadcrumbs,
                onNavigateToPrefix = onNavigateToPrefix
            )
            SearchField(
                value = state.search,
                onValueChange = viewModel::onSearchChanged
            )
            HorizontalDivider()

            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    state.isLoading && state.items.isEmpty() ->
                        SkeletonListRows(count = 6, modifier = Modifier.fillMaxWidth())
                    state.errorMessage != null && state.items.isEmpty() ->
                        EmptyState(
                            icon = Icons.Outlined.WifiOff,
                            title = "Couldn't load this folder",
                            body = state.errorMessage,
                            actionLabel = "Retry",
                            onAction = viewModel::refresh
                        )
                    state.visibleItems.isEmpty() && state.search.isNotBlank() ->
                        EmptyState(
                            icon = Icons.Outlined.Search,
                            title = "No matches",
                            body = "Nothing in this folder matches \"${state.search}\"."
                        )
                    state.visibleItems.isEmpty() ->
                        EmptyState(
                            icon = Icons.Outlined.FolderOpen,
                            title = "This folder is empty",
                            body = "Tap Upload to add a file, or use New folder to add a subfolder."
                        )
                    else ->
                        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                            items(state.visibleItems, key = { itemKey(it) }) { item ->
                                ItemRow(
                                    item = item,
                                    bucket = bucket,
                                    presigner = { key -> viewModel.presignThumbnail(key) },
                                    onClick = {
                                        when (item) {
                                            is S3Item.Folder -> onNavigateToPrefix(item.prefix)
                                            is S3Item.Object -> onOpenFile(item.key)
                                        }
                                    },
                                    onDownload = if (item is S3Item.Object) {
                                        {
                                            viewModel.enqueueDownload(item)
                                            scope.launch {
                                                snackbarHostState.showSnackbar(
                                                    "Download scheduled: ${item.displayName}"
                                                )
                                            }
                                        }
                                    } else null,
                                    onRename = if (item is S3Item.Object) {
                                        { viewModel.showRenameDialog(item) }
                                    } else null,
                                    onDelete = { viewModel.showDeleteDialog(item) }
                                )
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            }
                            if (state.isLoadingMore) {
                                item {
                                    Box(
                                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                                        contentAlignment = Alignment.Center
                                    ) { CircularProgressIndicator() }
                                }
                            }
                        }
                }
            }
        }
    }

    BrowserDialogs(
        dialog = state.dialog,
        operationInFlight = state.operationInFlight,
        onDismiss = viewModel::dismissDialog,
        onConfirmDelete = viewModel::confirmDelete,
        onConfirmRename = viewModel::confirmRename,
        onConfirmCreateFolder = viewModel::confirmCreateFolder
    )
}

private fun itemKey(item: S3Item): String = when (item) {
    is S3Item.Folder -> "F:${item.prefix}"
    is S3Item.Object -> "O:${item.key}"
}

@Composable
private fun BreadcrumbRow(
    bucket: String,
    breadcrumbs: List<Pair<String, String>>,
    onNavigateToPrefix: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = { onNavigateToPrefix("") }) {
            Text(bucket, style = MaterialTheme.typography.labelLarge)
        }
        breadcrumbs.forEach { (label, p) ->
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = { onNavigateToPrefix(p) }) {
                Text(label, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        singleLine = true,
        placeholder = { Text("Search this folder") },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        shape = RoundedCornerShape(28.dp)
    )
}

@Composable
private fun ItemRow(
    item: S3Item,
    bucket: String,
    presigner: suspend (String) -> String?,
    onClick: () -> Unit,
    onDownload: (() -> Unit)?,
    onRename: (() -> Unit)?,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    ListItem(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        leadingContent = {
            when (item) {
                is S3Item.Folder -> FolderAvatar()
                is S3Item.Object -> ObjectAvatar(item = item, bucket = bucket, presigner = presigner)
            }
        },
        headlineContent = {
            Text(
                text = item.displayName,
                maxLines = 1,
                style = MaterialTheme.typography.bodyLarge
            )
        },
        supportingContent = {
            Text(
                text = when (item) {
                    is S3Item.Folder -> "Folder"
                    is S3Item.Object -> objectSubtitle(item)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onDownload != null) {
                    IconButton(onClick = onDownload) {
                        Icon(
                            Icons.Outlined.Download,
                            contentDescription = "Download",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = "More",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (onRename != null) {
                        DropdownMenuItem(
                            text = { Text("Rename") },
                            onClick = { menuOpen = false; onRename() }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        onClick = { menuOpen = false; onDelete() }
                    )
                }
            }
        }
    )
}

@Composable
private fun FolderAvatar() {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.size(44.dp)
    ) {
        Icon(
            imageVector = Icons.Outlined.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(10.dp)
        )
    }
}

@Composable
private fun ObjectAvatar(
    item: S3Item.Object,
    bucket: String,
    presigner: suspend (String) -> String?
) {
    val kind = PreviewMimeDetector.kindFor(item.key)
    if (kind == PreviewKind.IMAGE) {
        ImageThumbnail(objectKey = item.key, presigner = presigner)
    } else {
        FileTypeAvatar(icon = iconFor(kind))
    }
}

@Composable
private fun ImageThumbnail(objectKey: String, presigner: suspend (String) -> String?) {
    var url by remember(objectKey) { mutableStateOf<String?>(null) }
    LaunchedEffect(objectKey) {
        url = presigner(objectKey)
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.size(44.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (url != null) {
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp))
                )
            } else {
                Icon(
                    imageVector = Icons.Outlined.Image,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun FileTypeAvatar(icon: ImageVector) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.size(44.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(10.dp)
        )
    }
}

private fun iconFor(kind: PreviewKind): ImageVector = when (kind) {
    PreviewKind.PDF -> Icons.Outlined.PictureAsPdf
    PreviewKind.VIDEO -> Icons.Outlined.VideoFile
    PreviewKind.TEXT -> Icons.Outlined.Code
    PreviewKind.IMAGE -> Icons.Outlined.Image  // unreachable from FileTypeAvatar, kept for completeness
    PreviewKind.UNSUPPORTED -> Icons.Outlined.InsertDriveFile
}

private fun objectSubtitle(obj: S3Item.Object): String =
    "${formatSize(obj.sizeBytes)} · ${formatDate(obj.lastModifiedEpochMs)}"

@Composable
private fun SortMenu(
    current: BrowserViewModel.UiState.Sort,
    onSortChanged: (BrowserViewModel.UiState.Sort) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    TextButton(onClick = { expanded = true }) {
        Text("Sort")
        Spacer(Modifier.width(4.dp))
        Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        BrowserViewModel.UiState.Sort.entries.forEach { sort ->
            DropdownMenuItem(
                text = { Text(sortLabel(sort)) },
                onClick = {
                    onSortChanged(sort)
                    expanded = false
                }
            )
        }
    }
}

private fun sortLabel(sort: BrowserViewModel.UiState.Sort): String = when (sort) {
    BrowserViewModel.UiState.Sort.NAME_ASC -> "Name (A→Z)"
    BrowserViewModel.UiState.Sort.NAME_DESC -> "Name (Z→A)"
    BrowserViewModel.UiState.Sort.MODIFIED_DESC -> "Most recently modified"
    BrowserViewModel.UiState.Sort.SIZE_DESC -> "Largest first"
}

private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return String.format(Locale.US, "%.1f %s", value, units[unit])
}

private fun formatDate(epochMs: Long): String {
    if (epochMs <= 0) return "—"
    return DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(epochMs))
}

// ---- Dialogs -------------------------------------------------------------

@Composable
private fun BrowserDialogs(
    dialog: BrowserViewModel.UiState.Dialog?,
    operationInFlight: Boolean,
    onDismiss: () -> Unit,
    onConfirmDelete: () -> Unit,
    onConfirmRename: (String) -> Unit,
    onConfirmCreateFolder: (String) -> Unit
) {
    when (dialog) {
        null -> Unit
        is BrowserViewModel.UiState.Dialog.ConfirmDelete -> AlertDialog(
            onDismissRequest = { if (!operationInFlight) onDismiss() },
            title = { Text("Delete?") },
            text = {
                val name = dialog.item.displayName
                Text(
                    when (dialog.item) {
                        is S3Item.Folder -> "Delete \"$name\" and everything inside it? This can't be undone."
                        is S3Item.Object -> "Delete \"$name\"? This can't be undone."
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = onConfirmDelete, enabled = !operationInFlight) {
                    Text(if (operationInFlight) "Deleting…" else "Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss, enabled = !operationInFlight) { Text("Cancel") }
            }
        )

        is BrowserViewModel.UiState.Dialog.Rename -> {
            var name by rememberSaveable(dialog.item.key) { mutableStateOf(dialog.initialName) }
            AlertDialog(
                onDismissRequest = { if (!operationInFlight) onDismiss() },
                title = { Text("Rename") },
                text = {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        label = { Text("New name") }
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = { onConfirmRename(name) },
                        enabled = !operationInFlight && name.isNotBlank()
                    ) { Text(if (operationInFlight) "Renaming…" else "Rename") }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss, enabled = !operationInFlight) { Text("Cancel") }
                }
            )
        }

        BrowserViewModel.UiState.Dialog.NewFolder -> {
            var name by rememberSaveable { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { if (!operationInFlight) onDismiss() },
                title = { Text("New folder") },
                icon = {
                    Icon(
                        imageVector = Icons.Outlined.CreateNewFolder,
                        contentDescription = null
                    )
                },
                text = {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        label = { Text("Folder name") }
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = { onConfirmCreateFolder(name) },
                        enabled = !operationInFlight && name.isNotBlank()
                    ) { Text(if (operationInFlight) "Creating…" else "Create") }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss, enabled = !operationInFlight) { Text("Cancel") }
                }
            )
        }
    }
}
