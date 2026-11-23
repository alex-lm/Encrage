@file:OptIn(UnstableApi::class)

package com.encrage.vault.ui.photos

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import androidx.media3.ui.compose.state.rememberPlayPauseButtonState
import androidx.media3.ui.compose.state.rememberProgressStateWithTickInterval
import androidx.paging.compose.LazyPagingItems
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.encrage.vault.data.local.BackupStatus
import com.encrage.vault.data.local.LocalState
import com.encrage.vault.data.local.MediaEntity
import com.encrage.vault.data.local.MediaType
import com.encrage.vault.media.localTileModel
import com.encrage.vault.ui.captureDateTime
import com.encrage.vault.ui.formatBytes
import com.encrage.vault.ui.formatDurationMs
import kotlinx.coroutines.launch
import kotlin.math.abs

@Composable
fun MediaViewer(
    media: LazyPagingItems<MediaEntity>,
    initialIndex: Int,
    onLoadCloudThumbnail: suspend (String) -> Bitmap?,
    onLoadCloudPhoto: suspend (MediaEntity) -> Bitmap?,
    onCloudVideoDataSource: (MediaEntity) -> DataSource.Factory,
    onDownload: (MediaEntity) -> Unit,
    onDelete: (MediaEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val pagerState = rememberPagerState(initialPage = initialIndex) { media.itemCount }
        var chromeVisible by remember { mutableStateOf(true) }
        var dismissOffset by remember { mutableFloatStateOf(0f) }
        val scope = rememberCoroutineScope()
        ImmersiveSystemBars(visible = chromeVisible)
        LaunchedEffect(media.itemCount) { if (media.itemCount == 0) onDismiss() }

        val dragProgress = (abs(dismissOffset) / 900f).coerceIn(0f, 1f)
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 1f - dragProgress * 0.85f))
                .draggable(
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState { delta -> dismissOffset += delta },
                    onDragStopped = { velocity ->
                        if (abs(dismissOffset) > 260f || abs(velocity) > 2200f) {
                            onDismiss()
                        } else {
                            scope.launch { animate(dismissOffset, 0f) { value, _ -> dismissOffset = value } }
                        }
                    },
                ),
        ) {
            HorizontalPager(
                state = pagerState,
                key = { index -> (if (index < media.itemCount) media.peek(index)?.localId else null) ?: "page-$index" },
                beyondViewportPageCount = 1,
                pageSpacing = 12.dp,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    translationY = dismissOffset
                    val shrink = 1f - dragProgress * 0.2f
                    scaleX = shrink
                    scaleY = shrink
                },
            ) { page ->
                val item = if (page < media.itemCount) media[page] else null
                val isCurrent = pagerState.settledPage == page
                when {
                    item == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color.White)
                    }
                    item.mediaType == MediaType.VIDEO -> VideoPage(
                        item = item,
                        isCurrent = isCurrent,
                        chromeVisible = chromeVisible,
                        onToggleChrome = { chromeVisible = !chromeVisible },
                        onCloudVideoDataSource = onCloudVideoDataSource,
                        onLoadCloudThumbnail = onLoadCloudThumbnail,
                    )
                    else -> ZoomableBox(isCurrent = isCurrent, onTap = { chromeVisible = !chromeVisible }) {
                        when {
                            item.mediaStoreUri != null -> LocalPhoto(item.mediaStoreUri)
                            item.mediaId != null -> CloudPhoto(item, isCurrent, onLoadCloudThumbnail, onLoadCloudPhoto)
                            else -> Unavailable("This item is no longer available")
                        }
                    }
                }
            }

            val current = if (pagerState.currentPage < media.itemCount) media.peek(pagerState.currentPage) else null
            AnimatedVisibility(
                visible = chromeVisible && current != null && dragProgress == 0f,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                current?.let { ViewerTopBar(it, onBack = onDismiss) }
            }
            AnimatedVisibility(
                visible = chromeVisible && current != null && dragProgress == 0f,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                current?.let { ViewerBottomBar(it, onDownload = { onDownload(it) }, onDelete = { onDelete(it) }) }
            }
        }
    }
}

/** Hides the status and navigation bars while the viewer chrome is hidden (swipe from an edge to peek). */
@Composable
private fun ImmersiveSystemBars(visible: Boolean) {
    val view = LocalView.current
    LaunchedEffect(visible) {
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (visible) controller.show(WindowInsetsCompat.Type.systemBars())
        else controller.hide(WindowInsetsCompat.Type.systemBars())
    }
}

@Composable
private fun ViewerTopBar(item: MediaEntity, onBack: () -> Unit) {
    val (date, time) = captureDateTime(item.dateTimeTakenEpochMs)
    val details = buildList {
        add(time)
        if (item.sizeBytes > 0) add(formatBytes(item.sizeBytes))
        if (item.width > 0 && item.height > 0) add("${item.width}×${item.height}")
    }.joinToString(" · ")
    Row(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .7f), Color.Transparent)))
            .statusBarsPadding()
            .padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Close", tint = Color.White) }
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(date, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Text(
                details,
                color = Color.White.copy(alpha = .8f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ViewerBottomBar(item: MediaEntity, onDownload: () -> Unit, onDelete: () -> Unit) {
    val (icon, label) = when {
        item.localState == LocalState.CLOUD_ONLY -> Icons.Rounded.Cloud to "Only in cloud"
        item.localState == LocalState.LOCAL_AND_CLOUD -> Icons.Rounded.CloudDone to "Backed up"
        item.backupStatus == BackupStatus.FAILED -> Icons.Rounded.ErrorOutline to "Backup failed"
        else -> Icons.Outlined.CloudUpload to "Waiting to back up"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .7f))))
            .navigationBarsPadding()
            .padding(start = 20.dp, end = 8.dp, top = 28.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Color.White.copy(alpha = .85f), modifier = Modifier.size(18.dp))
        Text(label, color = Color.White.copy(alpha = .85f), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 8.dp))
        Spacer(Modifier.weight(1f))
        if (item.localState == LocalState.CLOUD_ONLY && item.mediaId != null) {
            TextButton(onClick = onDownload) {
                Icon(Icons.Rounded.Download, null, tint = Color.White)
                Text("Save", color = Color.White, modifier = Modifier.padding(start = 6.dp))
            }
        }
        TextButton(onClick = onDelete) {
            Icon(Icons.Rounded.Delete, null, tint = Color.White)
            Text("Delete", color = Color.White, modifier = Modifier.padding(start = 6.dp))
        }
    }
}

/**
 * Pinch to zoom (anchored at the fingers), pan while zoomed, double-tap to zoom at the tapped point.
 * At 1× single-finger drags pass through so the pager and swipe-to-dismiss keep working.
 */
@Composable
private fun ZoomableBox(isCurrent: Boolean, onTap: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val scope = rememberCoroutineScope()
    val onTapState by rememberUpdatedState(onTap)

    fun clamp(value: Offset, atScale: Float): Offset {
        val maxX = size.width * (atScale - 1f) / 2f
        val maxY = size.height * (atScale - 1f) / 2f
        return Offset(value.x.coerceIn(-maxX, maxX), value.y.coerceIn(-maxY, maxY))
    }

    fun animateTo(targetScale: Float, targetOffset: Offset) {
        val startScale = scale
        val startOffset = offset
        scope.launch {
            animate(0f, 1f) { t, _ ->
                scale = startScale + (targetScale - startScale) * t
                offset = startOffset + (targetOffset - startOffset) * t
            }
        }
    }

    LaunchedEffect(isCurrent) {
        if (!isCurrent) {
            scale = 1f
            offset = Offset.Zero
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTapState() },
                    onDoubleTap = { tap ->
                        if (scale > 1.05f) {
                            animateTo(1f, Offset.Zero)
                        } else {
                            val target = DOUBLE_TAP_SCALE
                            val center = Offset(size.width / 2f, size.height / 2f)
                            animateTo(target, clamp((center - tap) * (target - 1f), target))
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val fingers = event.changes.count { it.pressed }
                        if (fingers >= 2 || scale > 1.01f) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val centroid = event.calculateCentroid(useCurrent = true)
                            val newScale = (scale * zoom).coerceIn(1f, MAX_SCALE)
                            if (centroid != Offset.Unspecified && scale > 0f) {
                                val center = Offset(size.width / 2f, size.height / 2f)
                                val anchored = (centroid - center) - (centroid - center - offset) * (newScale / scale) + pan
                                offset = clamp(anchored, newScale)
                            }
                            scale = newScale
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                    if (scale < 1.02f) {
                        scale = 1f
                        offset = Offset.Zero
                    }
                }
            },
    ) {
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
            contentAlignment = Alignment.Center,
            content = content,
        )
    }
}

private const val MAX_SCALE = 5f
private const val DOUBLE_TAP_SCALE = 2.5f

@Composable
private fun LocalPhoto(uri: String) {
    val context = LocalContext.current
    // The platform thumbnail paints instantly; the full-resolution decode fades in over it.
    AsyncImage(model = localTileModel(uri), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
    AsyncImage(
        model = remember(uri) { ImageRequest.Builder(context).data(Uri.parse(uri)).size(FULL_IMAGE_EDGE).build() },
        contentDescription = "Photo",
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize(),
    )
}

private const val FULL_IMAGE_EDGE = 2560

@Composable
private fun CloudPhoto(
    item: MediaEntity,
    isCurrent: Boolean,
    onLoadThumbnail: suspend (String) -> Bitmap?,
    onLoadOriginal: suspend (MediaEntity) -> Bitmap?,
) {
    val mediaId = item.mediaId ?: return
    // Decrypt the original only once the page is actually viewed, not while it is a swipe neighbour.
    var wantOriginal by remember(item.localId) { mutableStateOf(false) }
    LaunchedEffect(isCurrent) { if (isCurrent) wantOriginal = true }
    // (finished, bitmap): finished with a null bitmap means the original could not be fetched or verified.
    var attempt by remember(item.localId) { mutableIntStateOf(0) }
    val original by produceState<Pair<Boolean, Bitmap?>>(false to null, item.localId, wantOriginal, attempt) {
        value = false to null
        if (wantOriginal) value = true to onLoadOriginal(item)
    }
    val (finished, bitmap) = original
    CloudThumbnail(mediaId, onLoadThumbnail, ContentScale.Fit, Modifier.fillMaxSize(), showProgress = false)
    when {
        bitmap != null -> Image(bitmap.asImageBitmap(), "Photo", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        wantOriginal && !finished -> CircularProgressIndicator(Modifier.size(28.dp), color = Color.White.copy(alpha = .8f), strokeWidth = 2.dp)
        finished -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Unavailable("Showing a preview — the original couldn’t be loaded")
            TextButton(onClick = { attempt++ }) { Text("Try again", color = Color.White) }
        }
    }
}

@Composable
private fun Unavailable(message: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Rounded.CloudOff, null, tint = Color.White)
        Text(message, color = Color.White, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun VideoPoster(item: MediaEntity, onLoadCloudThumbnail: suspend (String) -> Bitmap?) {
    when {
        item.mediaStoreUri != null -> AsyncImage(
            model = localTileModel(item.mediaStoreUri), contentDescription = null,
            contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize(),
        )
        item.mediaId != null -> CloudThumbnail(item.mediaId, onLoadCloudThumbnail, ContentScale.Fit, Modifier.fillMaxSize(), showProgress = false)
    }
}

@Composable
private fun VideoPage(
    item: MediaEntity,
    isCurrent: Boolean,
    chromeVisible: Boolean,
    onToggleChrome: () -> Unit,
    onCloudVideoDataSource: (MediaEntity) -> DataSource.Factory,
    onLoadCloudThumbnail: suspend (String) -> Bitmap?,
) {
    val onTap by rememberUpdatedState(onToggleChrome)
    Box(
        Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) },
        contentAlignment = Alignment.Center,
    ) {
        if (!isCurrent) {
            // Only the settled page owns a player; neighbours show a poster.
            VideoPoster(item, onLoadCloudThumbnail)
            PlayBadge()
            return@Box
        }
        val context = LocalContext.current
        val player = remember(item.localId) {
            ExoPlayer.Builder(context).build().apply {
                val localUri = item.mediaStoreUri
                if (localUri != null) {
                    setMediaItem(MediaItem.fromUri(Uri.parse(localUri)))
                } else {
                    val source = ProgressiveMediaSource.Factory(onCloudVideoDataSource(item))
                        .createMediaSource(MediaItem.fromUri("encrage://cloud/${item.mediaId}"))
                    setMediaSource(source)
                }
                prepare()
                playWhenReady = true
            }
        }
        var error by remember(item.localId) { mutableStateOf(false) }
        var buffering by remember(item.localId) { mutableStateOf(true) }
        DisposableEffect(player) {
            val listener = object : Player.Listener {
                override fun onPlayerError(e: PlaybackException) { error = true }
                override fun onPlaybackStateChanged(state: Int) { buffering = state == Player.STATE_BUFFERING || state == Player.STATE_IDLE }
            }
            player.addListener(listener)
            onDispose {
                player.removeListener(listener)
                player.release()
            }
        }

        ContentFrame(player, Modifier.fillMaxSize(), SURFACE_TYPE_TEXTURE_VIEW, ContentScale.Fit)
        when {
            error -> Unavailable("Couldn’t play this video")
            buffering -> CircularProgressIndicator(color = Color.White.copy(alpha = .8f))
        }
        if (!error) {
            AnimatedVisibility(chromeVisible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
                VideoControls(player)
            }
        }
    }
}

@Composable
private fun PlayBadge() {
    Box(
        Modifier.size(64.dp).clip(CircleShape).background(Color.Black.copy(alpha = .45f)),
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(36.dp)) }
}

@Composable
private fun VideoControls(player: Player) {
    val playPause = rememberPlayPauseButtonState(player)
    val progress = rememberProgressStateWithTickInterval(player, 250L)
    var scrubbing by remember { mutableStateOf<Float?>(null) }
    val duration = progress.durationMs.coerceAtLeast(0L)
    val position = scrubbing?.let { (it * duration).toLong() } ?: progress.currentPositionMs

    Box(Modifier.fillMaxSize()) {
        IconButton(
            onClick = playPause::onClick,
            enabled = playPause.isEnabled,
            modifier = Modifier.align(Alignment.Center).size(72.dp).clip(CircleShape).background(Color.Black.copy(alpha = .45f)),
        ) {
            Icon(
                if (playPause.showPlay) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                if (playPause.showPlay) "Play" else "Pause",
                tint = Color.White,
                modifier = Modifier.size(40.dp),
            )
        }
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 64.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(formatDurationMs(position), color = Color.White, style = MaterialTheme.typography.labelMedium)
            Slider(
                value = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                onValueChange = { scrubbing = it },
                onValueChangeFinished = {
                    scrubbing?.let { player.seekTo((it * duration).toLong()) }
                    scrubbing = null
                },
                enabled = duration > 0,
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White,
                    inactiveTrackColor = Color.White.copy(alpha = .3f),
                ),
                modifier = Modifier.weight(1f),
            )
            Text(formatDurationMs(duration), color = Color.White, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(0.dp))
        }
    }
}
