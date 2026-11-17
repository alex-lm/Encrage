package com.encrage.vault.ui.photos

import android.graphics.Bitmap
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import coil3.compose.AsyncImage
import com.encrage.vault.data.local.LocalState
import com.encrage.vault.data.local.MediaEntity
import com.encrage.vault.data.local.MediaType
import com.encrage.vault.ui.BackupLocationFilter
import com.encrage.vault.ui.MediaActionUi
import com.encrage.vault.ui.MediaFilter
import com.encrage.vault.ui.PhotosFilters
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun PhotosScreen(
    media: LazyPagingItems<MediaEntity>,
    filters: PhotosFilters,
    actionUi: MediaActionUi,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    onSelectMediaFilter: (MediaFilter) -> Unit,
    onSelectLocationFilter: (BackupLocationFilter) -> Unit,
    onLoadCloudThumbnail: suspend (String) -> Bitmap?,
    onLoadCloudPhoto: suspend (MediaEntity) -> Bitmap?,
    onCloudVideoDataSource: (MediaEntity) -> DataSource.Factory,
    onDownload: (List<MediaEntity>) -> Unit,
    onDelete: (List<MediaEntity>) -> Unit,
    onActionMessageShown: () -> Unit,
) {
    var viewerItem by remember { mutableStateOf<MediaEntity?>(null) }
    var selectedItems by remember { mutableStateOf<Map<Long, MediaEntity>>(emptyMap()) }
    var confirmDelete by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(filters) { gridState.scrollToItem(0) }
    LaunchedEffect(actionUi.message, actionUi.error) {
        val notice = actionUi.error ?: actionUi.message
        if (notice != null) {
            snackbar.showSnackbar(notice)
            onActionMessageShown()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = selectedItems.isEmpty(),
        drawerContent = {
            ModalDrawerSheet(Modifier.fillMaxHeight().fillMaxWidth(.86f).widthIn(max = 360.dp)) {
                FiltersDrawer(
                    filters = filters,
                    onSelectMediaFilter = onSelectMediaFilter,
                    onSelectLocationFilter = onSelectLocationFilter,
                    onDone = { scope.launch { drawerState.close() } },
                )
            }
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "gallery-actions") {
                    if (selectedItems.isEmpty()) {
                        FilterButton(filters, actionUi.busy) { scope.launch { drawerState.open() } }
                    } else {
                        SelectionBar(
                            count = selectedItems.size,
                            canDownload = selectedItems.values.all {
                                it.localState == LocalState.CLOUD_ONLY && it.mediaId != null
                            },
                            busy = actionUi.busy,
                            onClear = { selectedItems = emptyMap() },
                            onSelectAll = {
                                selectedItems = buildMap {
                                    for (index in 0 until media.itemCount) {
                                        media.peek(index)?.let { put(it.localId, it) }
                                    }
                                }
                            },
                            onDownload = {
                                onDownload(selectedItems.values.toList())
                                selectedItems = emptyMap()
                            },
                            onDelete = { confirmDelete = true },
                        )
                    }
                }
                if (!hasPermission) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "permission") {
                        PermissionCard(onRequestPermission)
                    }
                }
                for (index in 0 until media.itemCount) {
                    val item = media.peek(index)
                    val previous = if (index > 0) media.peek(index - 1) else null
                    if (item != null && (previous == null || day(item) != day(previous))) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "date-${day(item)}-${item.localId}") {
                            Text(
                                dateLabel(item),
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(start = 14.dp, top = 20.dp, bottom = 8.dp),
                            )
                        }
                    }
                    item(key = "gallery-slot-$index") {
                        val loaded = media[index]
                        if (loaded == null) {
                            MediaTilePlaceholder()
                        } else {
                            MediaTile(
                                item = loaded,
                                selected = loaded.localId in selectedItems,
                                onLoadCloudThumbnail = onLoadCloudThumbnail,
                                onTap = {
                                    if (selectedItems.isEmpty()) {
                                        viewerItem = loaded
                                    } else {
                                        selectedItems = selectedItems.toggle(loaded)
                                    }
                                },
                                onLongPress = { selectedItems = selectedItems + (loaded.localId to loaded) },
                            )
                        }
                    }
                }
                if (media.loadState.refresh is LoadState.Loading || media.loadState.append is LoadState.Loading) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                }
            }
            GridScrollBar(gridState)
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(12.dp))
        }
    }

    viewerItem?.let {
        FullscreenViewer(
            item = it,
            onLoadCloudPhoto = onLoadCloudPhoto,
            onCloudVideoDataSource = onCloudVideoDataSource,
            onDismiss = { viewerItem = null },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete selected media?") },
            text = { Text("This removes ${selectedItems.size} selected ${if (selectedItems.size == 1) "item" else "items"} from the phone and Encrage cloud where present.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete(selectedItems.values.toList())
                    selectedItems = emptyMap()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

private fun Map<Long, MediaEntity>.toggle(item: MediaEntity): Map<Long, MediaEntity> =
    if (item.localId in this) this - item.localId else this + (item.localId to item)

@Composable
private fun FilterButton(filters: PhotosFilters, busy: Boolean, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onOpen, enabled = !busy) { Icon(Icons.Rounded.FilterList, "Open filters") }
        Text(
            if (filters == PhotosFilters()) "Filters" else "${mediaFilterLabel(filters.media)} · ${locationFilterLabel(filters.location)}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SelectionBar(
    count: Int,
    canDownload: Boolean,
    busy: Boolean,
    onClear: () -> Unit,
    onSelectAll: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClear, enabled = !busy) { Icon(Icons.Rounded.Close, "Clear selection") }
        Text("$count selected", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onSelectAll, enabled = !busy) { Icon(Icons.Rounded.SelectAll, "Select all") }
        IconButton(onClick = onDownload, enabled = canDownload && !busy) { Icon(Icons.Rounded.Download, "Download selected") }
        IconButton(onClick = onDelete, enabled = !busy) { Icon(Icons.Rounded.Delete, "Delete selected") }
    }
}

@Composable
private fun FiltersDrawer(
    filters: PhotosFilters,
    onSelectMediaFilter: (MediaFilter) -> Unit,
    onSelectLocationFilter: (BackupLocationFilter) -> Unit,
    onDone: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 28.dp)) {
        Text("Filters", style = MaterialTheme.typography.headlineSmall)
        Text("Narrow the gallery without taking space from your photos.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp, bottom = 28.dp))
        FilterDropdown(
            label = "Media Type",
            value = mediaFilterLabel(filters.media),
            options = MediaFilter.entries.map { it to mediaFilterLabel(it) },
            onSelect = onSelectMediaFilter,
        )
        Spacer(Modifier.height(20.dp))
        FilterDropdown(
            label = "Backup Status",
            value = locationFilterLabel(filters.location),
            options = BackupLocationFilter.entries.map { it to locationFilterLabel(it) },
            onSelect = onSelectLocationFilter,
        )
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
            TextButton(onClick = {
                onSelectMediaFilter(MediaFilter.ALL)
                onSelectLocationFilter(BackupLocationFilter.ALL)
            }) { Text("Reset") }
            Button(onClick = onDone) { Text("Done") }
        }
    }
}

@Composable
private fun <T> FilterDropdown(
    label: String,
    value: String,
    options: List<Pair<T, String>>,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(bottom = 7.dp))
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(value)
                Spacer(Modifier.weight(1f))
                Icon(Icons.Rounded.ExpandMore, null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (option, text) ->
                    DropdownMenuItem(
                        text = { Text(text) },
                        onClick = {
                            onSelect(option)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

private fun mediaFilterLabel(filter: MediaFilter) = when (filter) {
    MediaFilter.ALL -> "All Media"
    MediaFilter.PHOTOS -> "Photos"
    MediaFilter.VIDEOS -> "Videos"
}

private fun locationFilterLabel(filter: BackupLocationFilter) = when (filter) {
    BackupLocationFilter.ALL -> "All backups"
    BackupLocationFilter.PHONE_ONLY -> "Phone only"
    BackupLocationFilter.CLOUD_ONLY -> "Cloud only"
    BackupLocationFilter.PHONE_AND_CLOUD -> "Phone + Cloud"
}

@Composable
private fun BoxScope.GridScrollBar(state: LazyGridState) {
    val layoutInfo = state.layoutInfo
    val totalItems = layoutInfo.totalItemsCount
    val visibleItems = layoutInfo.visibleItemsInfo.size
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    val canScroll = totalItems > visibleItems && visibleItems > 0

    LaunchedEffect(state.isScrollInProgress, dragging, canScroll) {
        if (!canScroll) {
            visible = false
            return@LaunchedEffect
        }
        if (state.isScrollInProgress || dragging) {
            visible = true
        } else {
            delay(900)
            visible = false
        }
    }
    if (!visible || !canScroll) return

    BoxWithConstraints(
        Modifier
            .align(Alignment.CenterEnd)
            .fillMaxHeight()
            .width(28.dp)
            .padding(vertical = 4.dp, horizontal = 4.dp),
    ) {
        val trackHeight = maxHeight
        val trackHeightPx = with(LocalDensity.current) { trackHeight.toPx() }.coerceAtLeast(1f)
        val thumbHeight = (trackHeight * (visibleItems.toFloat() / totalItems)).coerceIn(48.dp, trackHeight)
        val progress = (
            state.firstVisibleItemIndex.toFloat() / (totalItems - visibleItems).coerceAtLeast(1)
        ).coerceIn(0f, 1f)

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(totalItems, visibleItems, trackHeightPx) {
                    fun scrollToY(y: Float) {
                        val fraction = (y / trackHeightPx).coerceIn(0f, 1f)
                        val maxIndex = (totalItems - visibleItems).coerceAtLeast(0)
                        val target = (fraction * maxIndex).toInt().coerceIn(0, (totalItems - 1).coerceAtLeast(0))
                        scope.launch { state.scrollToItem(target) }
                    }
                    detectDragGestures(
                        onDragStart = { offset ->
                            dragging = true
                            scrollToY(offset.y)
                        },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                        onDrag = { change, _ ->
                            change.consume()
                            scrollToY(change.position.y)
                        },
                    )
                },
        ) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(y = (trackHeight - thumbHeight) * progress)
                    .width(14.dp)
                    .height(thumbHeight)
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .75f)),
            )
        }
    }
}

@Composable
private fun PermissionCard(onRequest: () -> Unit) {
    Card(Modifier.padding(16.dp).fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Text("Allow access to all photos and videos", style = MaterialTheme.typography.titleMedium)
            Text("Full library access is required for reliable automatic backup. Selected access will still work, but backup is limited.", modifier = Modifier.padding(vertical = 8.dp))
            Button(onRequest) { Text("Choose access") }
        }
    }
}

@Composable
private fun MediaTilePlaceholder() {
    Box(Modifier.aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceContainerLow))
}

@Composable
private fun MediaTile(
    item: MediaEntity,
    selected: Boolean,
    onLoadCloudThumbnail: suspend (String) -> Bitmap?,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    Box(
        Modifier.aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceContainer)
            .then(
                if (selected) {
                    Modifier
                        .border(4.dp, MaterialTheme.colorScheme.onPrimary)
                        .border(2.dp, MaterialTheme.colorScheme.primary)
                } else Modifier
            )
            .pointerInput(item.localId) {
                detectTapGestures(onTap = { onTap() }, onLongPress = { onLongPress() })
            },
    ) {
        if (item.mediaStoreUri != null) {
            AsyncImage(
                model = Uri.parse(item.mediaStoreUri), contentDescription = null,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
            )
        } else if (item.mediaId != null) {
            CloudThumbnail(item.mediaId, onLoadCloudThumbnail, ContentScale.Crop, Modifier.fillMaxSize())
        } else {
            Icon(Icons.Rounded.CloudOff, "Unavailable item", modifier = Modifier.align(Alignment.Center), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (item.mediaType == MediaType.VIDEO) {
            Row(
                Modifier.align(Alignment.BottomEnd).padding(6.dp).clip(MaterialTheme.shapes.small)
                    .background(Color.Black.copy(alpha = .6f)).padding(horizontal = 5.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(14.dp))
                item.durationSeconds?.let { Text(duration(it), color = Color.White, style = MaterialTheme.typography.labelSmall) }
            }
        }
        if (selected) {
            Icon(
                Icons.Rounded.CheckCircle,
                "Selected",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp).size(25.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface),
            )
        }
        val (statusIcon, statusDescription) = when (item.localState) {
            LocalState.LOCAL_ONLY -> Icons.Rounded.Close to "On phone only"
            LocalState.CLOUD_ONLY -> Icons.Rounded.Cloud to "In cloud only"
            LocalState.LOCAL_AND_CLOUD -> Icons.Rounded.Check to "On phone and in cloud"
            LocalState.MISSING -> Icons.Rounded.Close to "Unavailable"
        }
        Icon(
            statusIcon, statusDescription, tint = Color.White,
            modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(25.dp).clip(CircleShape)
                .background(Color.Black.copy(alpha = .68f)).padding(5.dp),
        )
    }
}

@Composable
private fun FullscreenViewer(
    item: MediaEntity,
    onLoadCloudPhoto: suspend (MediaEntity) -> Bitmap?,
    onCloudVideoDataSource: (MediaEntity) -> DataSource.Factory,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            val localUri = item.mediaStoreUri?.let(Uri::parse)
            when {
                item.mediaType == MediaType.VIDEO && localUri != null -> VideoPlayer(localUri = localUri)
                item.mediaType == MediaType.VIDEO && item.mediaId != null -> VideoPlayer(
                    cloudItem = item,
                    cloudDataSource = onCloudVideoDataSource(item),
                )
                localUri != null -> ZoomableLocalPhoto(localUri)
                item.mediaId != null -> CloudPhoto(item, onLoadCloudPhoto)
                else -> Icon(Icons.Rounded.CloudOff, "Media unavailable", tint = Color.White, modifier = Modifier.align(Alignment.Center))
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp).clip(CircleShape).background(Color.Black.copy(alpha = .55f)),
            ) { Icon(Icons.Rounded.Close, "Close", tint = Color.White) }
        }
    }
}

@Composable
private fun ZoomableLocalPhoto(uri: Uri) {
    var scale by remember { mutableFloatStateOf(1f) }
    val transform = rememberTransformableState { zoom, _, _ -> scale = (scale * zoom).coerceIn(1f, 5f) }
    AsyncImage(
        model = uri, contentDescription = "Photo", contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale).transformable(transform)
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { scale = if (scale > 1f) 1f else 2.5f }) },
    )
}

@Composable
private fun CloudPhoto(item: MediaEntity, onLoad: suspend (MediaEntity) -> Bitmap?) {
    val state by produceState<CloudOriginalUiState>(CloudOriginalUiState.Loading, item.localId) {
        value = onLoad(item)?.let(CloudOriginalUiState::Ready) ?: CloudOriginalUiState.Unavailable
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (val current = state) {
            CloudOriginalUiState.Loading -> CircularProgressIndicator(color = Color.White)
            CloudOriginalUiState.Unavailable -> Icon(Icons.Rounded.CloudOff, "Cloud original unavailable", tint = Color.White)
            is CloudOriginalUiState.Ready -> Image(
                current.bitmap.asImageBitmap(), "Photo", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private sealed interface CloudOriginalUiState {
    data object Loading : CloudOriginalUiState
    data object Unavailable : CloudOriginalUiState
    data class Ready(val bitmap: Bitmap) : CloudOriginalUiState
}

private sealed interface CloudThumbnailUiState {
    data object Loading : CloudThumbnailUiState
    data object Unavailable : CloudThumbnailUiState
    data class Ready(val bitmap: Bitmap) : CloudThumbnailUiState
}

@Composable
private fun CloudThumbnail(
    mediaId: String,
    onLoad: suspend (String) -> Bitmap?,
    contentScale: ContentScale,
    modifier: Modifier = Modifier,
) {
    val state by produceState<CloudThumbnailUiState>(CloudThumbnailUiState.Loading, mediaId) {
        value = onLoad(mediaId)?.let(CloudThumbnailUiState::Ready) ?: CloudThumbnailUiState.Unavailable
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        when (val current = state) {
            CloudThumbnailUiState.Loading -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            CloudThumbnailUiState.Unavailable -> Icon(Icons.Rounded.CloudOff, "Cloud thumbnail unavailable", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            is CloudThumbnailUiState.Ready -> Image(current.bitmap.asImageBitmap(), null, contentScale = contentScale, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
@OptIn(UnstableApi::class)
private fun VideoPlayer(
    localUri: Uri? = null,
    cloudItem: MediaEntity? = null,
    cloudDataSource: DataSource.Factory? = null,
) {
    val context = LocalContext.current
    val key = localUri?.toString() ?: cloudItem?.mediaId
    val player = remember(key) {
        ExoPlayer.Builder(context).build().apply {
            if (localUri != null) {
                setMediaItem(MediaItem.fromUri(localUri))
            } else {
                val item = checkNotNull(cloudItem)
                val source = ProgressiveMediaSource.Factory(checkNotNull(cloudDataSource)).createMediaSource(
                    MediaItem.fromUri("encrage://cloud/${item.mediaId}"),
                )
                setMediaSource(source)
            }
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(
        factory = {
            PlayerView(it).apply {
                this.player = player
                useController = true
                setShowPreviousButton(false)
                setShowNextButton(false)
                setShowRewindButton(false)
                setShowFastForwardButton(false)
                setShowShuffleButton(false)
            }
        },
        modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
    )
}

private fun day(item: MediaEntity): LocalDate =
    Instant.ofEpochMilli(item.dateTimeTakenEpochMs).atZone(ZoneId.systemDefault()).toLocalDate()

private fun dateLabel(item: MediaEntity): String {
    val date = day(item)
    val today = LocalDate.now()
    return when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> date.format(DateTimeFormatter.ofPattern("MMMM d, yyyy"))
    }
}

private fun duration(seconds: Long) = "%d:%02d".format(seconds / 60, seconds % 60)
