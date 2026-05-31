package com.mobildroid.cloudshelf.app.preview

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewScreen(
    bucket: String,
    key: String,
    onBack: () -> Unit,
    viewModel: PreviewViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    fun openShareSheet(url: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, viewModel.displayName)
            putExtra(Intent.EXTRA_TEXT, url)
        }
        context.startActivity(Intent.createChooser(intent, "Share ${viewModel.displayName}"))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(viewModel.displayName, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        viewModel.shareLink(
                            onReady = ::openShareSheet,
                            onError = { msg ->
                                scope.launch { snackbarHostState.showSnackbar(msg) }
                            }
                        )
                    }) {
                        Icon(Icons.Filled.Share, contentDescription = "Share link")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                is PreviewViewModel.PreviewState.Loading ->
                    LoadingProgress(bytesLoaded = s.bytesLoaded, totalBytes = s.totalBytes)
                is PreviewViewModel.PreviewState.Image ->
                    ZoomableAsyncImage(file = s.file)
                is PreviewViewModel.PreviewState.Pdf ->
                    PdfPagerPreview(file = s.file)
                is PreviewViewModel.PreviewState.Video ->
                    VideoPlayerPreview(url = s.url)
                is PreviewViewModel.PreviewState.Text ->
                    TextPreview(content = s.content, truncated = s.truncated)
                is PreviewViewModel.PreviewState.Unsupported ->
                    UnsupportedPreview(
                        mimeType = s.mimeType,
                        onDownload = {
                            viewModel.downloadFullObject {
                                scope.launch {
                                    snackbarHostState.showSnackbar("Download scheduled — check Transfers")
                                }
                            }
                        }
                    )
                is PreviewViewModel.PreviewState.Error ->
                    ErrorBlock(message = s.message, onRetry = viewModel::load)
            }
        }
    }
}

// ---- Loading progress (PDF + text fetch) ---------------------------------

@Composable
private fun LoadingProgress(bytesLoaded: Long, totalBytes: Long) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        androidx.compose.material3.Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.padding(horizontal = 32.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp)
            ) {
                if (totalBytes > 0) {
                    val fraction = (bytesLoaded.toFloat() / totalBytes).coerceIn(0f, 1f)
                    Text(
                        text = "${(fraction * 100).toInt()}%",
                        style = androidx.compose.material3.MaterialTheme.typography.headlineMedium
                    )
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "${formatBytes(bytesLoaded)} of ${formatBytes(totalBytes)}",
                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                } else {
                    CircularProgressIndicator()
                    Text(
                        "Preparing preview…",
                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return String.format(Locale.US, "%.1f %s", value, units[unit])
}

// ---- Image with pinch-zoom + pan -----------------------------------------

@Composable
private fun ZoomableAsyncImage(file: File) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    // Decode the cached file ourselves via BitmapFactory rather than going
    // through Coil. The Coil 3 RC's local-file rendering path proved unreliable
    // in this project, so we do the decode directly: predictable, no extra
    // fetcher / decoder plumbing, and we can show the real failure reason
    // if a file can't be decoded.
    var bitmap by remember(file) { mutableStateOf<Bitmap?>(null) }
    var errorMessage by remember(file) { mutableStateOf<String?>(null) }

    LaunchedEffect(file) {
        withContext(Dispatchers.IO) {
            runCatching { decodeSampledBitmap(file, MAX_DECODED_DIM) }
                .onSuccess { bmp ->
                    if (bmp == null) {
                        errorMessage = "Couldn't decode image (file may be corrupted)."
                    } else {
                        bitmap = bmp
                    }
                }
                .onFailure { e ->
                    errorMessage = "Couldn't decode image: ${e.message ?: e::class.simpleName}"
                }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 5f)
                    if (scale > 1f) {
                        offsetX += pan.x
                        offsetY += pan.y
                    } else {
                        offsetX = 0f
                        offsetY = 0f
                    }
                }
            }
    ) {
        when {
            bitmap != null -> Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offsetX,
                        translationY = offsetY
                    )
            )
            errorMessage != null -> Text(
                text = errorMessage!!,
                color = Color.White,
                modifier = Modifier.align(Alignment.Center).padding(24.dp)
            )
            else -> CircularProgressIndicator(
                color = Color.White,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

/** Cap on the longer dimension of decoded bitmaps to keep memory in check. */
private const val MAX_DECODED_DIM = 4096

/**
 * Decode a Bitmap from a local file, down-sampling so the longest dimension
 * doesn't exceed [maxDim]. Returns null if the file isn't a decodable image.
 */
private fun decodeSampledBitmap(file: File, maxDim: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var inSampleSize = 1
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    while (longest / inSampleSize > maxDim) inSampleSize *= 2

    val decode = BitmapFactory.Options().apply { this.inSampleSize = inSampleSize }
    return BitmapFactory.decodeFile(file.absolutePath, decode)
}

// ---- PDF (PdfRenderer + HorizontalPager) ---------------------------------

@Composable
private fun PdfPagerPreview(file: File) {
    val renderer = remember(file) {
        val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        PdfRenderer(fd)
    }
    DisposableEffect(renderer) {
        onDispose { runCatching { renderer.close() } }
    }
    val pageCount = renderer.pageCount
    val pagerState = rememberPagerState(pageCount = { pageCount })

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF222222))) {
        Text(
            text = "Page ${pagerState.currentPage + 1} of $pageCount",
            color = Color.White,
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { pageIndex ->
            val bitmap = remember(pageIndex) { renderPdfPage(renderer, pageIndex) }
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Page ${pageIndex + 1}",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/** Render a PDF page to a Bitmap at ~2x density for readable text. */
private fun renderPdfPage(renderer: PdfRenderer, pageIndex: Int): Bitmap {
    renderer.openPage(pageIndex).use { page ->
        val scale = 2
        val bmp = Bitmap.createBitmap(
            page.width * scale,
            page.height * scale,
            Bitmap.Config.ARGB_8888
        )
        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        return bmp
    }
}

// PdfRenderer.Page doesn't implement Closeable directly across all API levels —
// wrap with this so `use { }` works regardless.
private inline fun <T> PdfRenderer.Page.use(block: (PdfRenderer.Page) -> T): T {
    try {
        return block(this)
    } finally {
        close()
    }
}

// ---- Video (Media3 ExoPlayer) --------------------------------------------

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun VideoPlayerPreview(url: String) {
    val context = LocalContext.current
    var isBuffering by remember { mutableStateOf(true) }
    var bufferedPercent by remember { mutableIntStateOf(0) }

    val exoPlayer = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                isBuffering = state == Player.STATE_BUFFERING
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    // While buffering, poll bufferedPercentage so the overlay bar moves.
    // ExoPlayer doesn't push frequent enough events for a smooth bar
    // and the cost of polling at 5 Hz during a brief stall is negligible.
    LaunchedEffect(isBuffering) {
        while (isBuffering) {
            bufferedPercent = exoPlayer.bufferedPercentage
            delay(200)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = true
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        if (isBuffering) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (bufferedPercent > 0) {
                    LinearProgressIndicator(
                        progress = { bufferedPercent / 100f },
                        modifier = Modifier.fillMaxWidth(0.6f),
                        color = Color.White
                    )
                    Text(
                        text = "Buffering · $bufferedPercent%",
                        color = Color.White
                    )
                } else {
                    CircularProgressIndicator(color = Color.White)
                    Text("Buffering…", color = Color.White)
                }
            }
        }
    }
}

// ---- Text ----------------------------------------------------------------

@Composable
private fun TextPreview(content: String, truncated: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = content,
            fontFamily = FontFamily.Monospace
        )
        if (truncated) {
            Text(
                text = "\n— Preview truncated. Download the file to see the rest. —",
                modifier = Modifier.padding(top = 16.dp)
            )
        }
    }
}

// ---- Unsupported fallback -------------------------------------------------

@Composable
private fun UnsupportedPreview(mimeType: String, onDownload: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(modifier = Modifier.fillMaxSize().padding(top = 80.dp)) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("No in-app preview for $mimeType.")
                Text(
                    "Download the file and open it with another app.",
                    modifier = Modifier.padding(top = 8.dp)
                )
                Button(onClick = onDownload, modifier = Modifier.padding(top = 16.dp)) {
                    Text("Download to device")
                }
            }
        }
    }
}

// ---- Error ---------------------------------------------------------------

@Composable
private fun ErrorBlock(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(message)
        Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) {
            Text("Retry")
        }
    }
}
