package com.encrage.vault.ui.photos

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.media3.datasource.DataSource
import androidx.paging.compose.LazyPagingItems
import coil3.compose.AsyncImage
import com.encrage.vault.data.local.BackupStatus
import com.encrage.vault.data.local.LocalState
import com.encrage.vault.data.local.MediaEntity
import com.encrage.vault.data.local.MediaType
import com.encrage.vault.data.preferences.AppPreferences
import com.encrage.vault.media.localTileModel
import com.encrage.vault.ui.BackupLocationFilter
import com.encrage.vault.ui.DaySection
import com.encrage.vault.ui.GalleryTimeline
import com.encrage.vault.ui.MediaActionUi
import com.encrage.vault.ui.MediaFilter
import com.encrage.vault.ui.PhotosFilters
import com.encrage.vault.ui.formatDuration
import com.encrage.vault.ui.friendlyDay
import com.encrage.vault.ui.monthLabel
import com.encrage.vault.ui.plural
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun PhotosScreen(
    media: LazyPagingItems<MediaEntity>,
    timeline: GalleryTimeline,
    filters: PhotosFilters,
    actionUi: MediaActionUi,
    columns: Int,
    refreshing: Boolean,
    serverUnreachable: Boolean,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    onSelectMediaFilter: (MediaFilter) -> Unit,
    onSelectLocationFilter: (BackupLocationFilter) -> Unit,
    onColumnsChange: (Int) -> Unit,
    onRefresh: () -> Unit,
    onOpenBackup: () -> Unit,
    onLoadCloudThumbnail: suspend (String) -> Bitmap?,
    onLoadCloudPhoto: suspend (MediaEntity) -> Bitmap?,
    onCloudVideoDataSource: (MediaEntity) -> DataSource.Factory,
    onDownload: (List<MediaEntity>) -> Unit,
    onDelete: (List<MediaEntity>) -> Unit,
    onActionMessageShown: () -> Unit,
) {
    var viewerIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    var selectedItems by remember { mutableStateOf<Map<Long, MediaEntity>>(emptyMap()) }
    var pendingDelete by remember { mutableStateOf<List<MediaEntity>?>(null) }
    val selectionMode = selectedItems.isNotEmpty()
    val gridState = rememberLazyGridState()
    val snackbar = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val haptics = LocalHapticFeedback.current

    LaunchedEffect(filters) { gridState.scrollToItem(0) }
    LaunchedEffect(actionUi.message, actionUi.error) {
        val notice = actionUi.error ?: actionUi.message ?: return@LaunchedEffect
        snackbar.showSnackbar(notice)
        if (!actionUi.busy) onActionMessageShown()
    }
    BackHandler(enabled = selectionMode) { selectedItems = emptyMap() }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (selectionMode) {
                SelectionTopBar(
                    count = selectedItems.size,
                    canDownload = selectedItems.values.any { it.localState == LocalState.CLOUD_ONLY && it.mediaId != null },
                    busy = actionUi.busy,
                    onClear = { selectedItems = emptyMap() },
                    onSelectAll = {
                        selectedItems = buildMap {
                            for (index in 0 until media.itemCount) media.peek(index)?.let { put(it.localId, it) }
                        }
                    },
                    onDownload = {
                        onDownload(selectedItems.values.toList())
                        selectedItems = emptyMap()
                    },
                    onDelete = { pendingDelete = selectedItems.values.toList() },
                )
            } else {
                TopAppBar(
                    title = {
                        Column {
                            Text("Photos")
                            if (timeline.loaded && timeline.totalCount > 0) {
                                Text(
                                    plural(timeline.totalCount, "item"),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    actions = { GridSizeMenu(columns, onColumnsChange) },
                    windowInsets = WindowInsets(0),
                    scrollBehavior = scrollBehavior,
                    colors = TopAppBarDefaults.topAppBarColors(scrolledContainerColor = MaterialTheme.colorScheme.surface),
                )
            }
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = onRefresh,
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            val prefixItems = 1 + (if (serverUnreachable) 1 else 0) + (if (!hasPermission) 1 else 0)
            val gridStarts = remember(timeline, prefixItems) { sectionGridStarts(timeline.sections, prefixItems) }
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                state = gridState,
                modifier = Modifier.fillMaxSize().pinchToResize(columns, onColumnsChange),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "filters", contentType = "filters") {
                    FilterChipsRow(filters, enabled = !selectionMode, onSelectMediaFilter, onSelectLocationFilter)
                }
                if (serverUnreachable) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "offline", contentType = "banner") {
                        OfflineBanner(onOpenBackup)
                    }
                }
                if (!hasPermission) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "permission", contentType = "banner") {
                        PermissionCard(onRequestPermission)
                    }
                }
                if (timeline.loaded && timeline.sections.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "empty", contentType = "empty") {
                        EmptyGallery(
                            filtered = filters != PhotosFilters(),
                            onClearFilters = {
                                onSelectMediaFilter(MediaFilter.ALL)
                                onSelectLocationFilter(BackupLocationFilter.ALL)
                            },
                        )
                    }
                }
                timeline.sections.forEach { section ->
                    item(span = { GridItemSpan(maxLineSpan) }, key = "day-${section.day}", contentType = "header") {
                        val sectionItems = { (0 until section.count).mapNotNull { peekSafe(media, section.firstMediaIndex + it) } }
                        val allSelected = selectionMode && sectionItems().let { it.isNotEmpty() && it.all { m -> m.localId in selectedItems } }
                        DayHeader(
                            label = if (timeline.monthly) monthLabel(section.day) else friendlyDay(section.day),
                            selectionMode = selectionMode,
                            allSelected = allSelected,
                            onToggle = {
                                val items = sectionItems()
                                selectedItems = if (allSelected) {
                                    selectedItems - items.map { it.localId }.toSet()
                                } else {
                                    selectedItems + items.associateBy { it.localId }
                                }
                            },
                        )
                    }
                    items(
                        count = section.count,
                        key = { offset ->
                            val index = section.firstMediaIndex + offset
                            peekSafe(media, index)?.localId ?: "slot-$index"
                        },
                        contentType = { "tile" },
                    ) { offset ->
                        val index = section.firstMediaIndex + offset
                        val loaded = if (index < media.itemCount) media[index] else null
                        if (loaded == null) {
                            MediaTilePlaceholder()
                        } else {
                            MediaTile(
                                item = loaded,
                                selectionMode = selectionMode,
                                selected = loaded.localId in selectedItems,
                                onLoadCloudThumbnail = onLoadCloudThumbnail,
                                onTap = {
                                    if (selectionMode) {
                                        selectedItems = selectedItems.toggle(loaded)
                                    } else {
                                        viewerIndex = index
                                    }
                                },
                                onLongPress = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    selectedItems = selectedItems.toggle(loaded)
                                },
                            )
                        }
                    }
                }
            }
            FastScroller(gridState, timeline.sections, gridStarts)
        }
    }

    viewerIndex?.let { start ->
        MediaViewer(
            media = media,
            initialIndex = start.coerceIn(0, (media.itemCount - 1).coerceAtLeast(0)),
            onLoadCloudThumbnail = onLoadCloudThumbnail,
            onLoadCloudPhoto = onLoadCloudPhoto,
            onCloudVideoDataSource = onCloudVideoDataSource,
            onDownload = { onDownload(listOf(it)) },
            onDelete = { pendingDelete = listOf(it) },
            onDismiss = { viewerIndex = null },
        )
    }
    pendingDelete?.let { items ->
        DeleteConfirmDialog(
            items = items,
            onConfirm = {
                pendingDelete = null
                onDelete(items)
                selectedItems = selectedItems - items.map { it.localId }.toSet()
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

private fun peekSafe(media: LazyPagingItems<MediaEntity>, index: Int): MediaEntity? =
    if (index in 0 until media.itemCount) media.peek(index) else null

private fun Map<Long, MediaEntity>.toggle(item: MediaEntity): Map<Long, MediaEntity> =
    if (item.localId in this) this - item.localId else this + (item.localId to item)

/** Grid index of each section's header, so the scroller can map a scroll position to a date. */
private fun sectionGridStarts(sections: List<DaySection>, prefixItems: Int): IntArray {
    val starts = IntArray(sections.size)
    var position = prefixItems
    sections.forEachIndexed { i, section ->
        starts[i] = position
        position += 1 + section.count
    }
    return starts
}

/** Two-finger pinch changes the column count; single-finger scrolling is untouched. */
private fun Modifier.pinchToResize(columns: Int, onColumnsChange: (Int) -> Unit): Modifier = composed {
    val currentColumns by rememberUpdatedState(columns)
    val onChange by rememberUpdatedState(onColumnsChange)
    pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var zoom = 1f
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.count { it.pressed } >= 2) {
                    zoom *= event.calculateZoom()
                    event.changes.forEach { it.consume() }
                    val next = when {
                        zoom > 1.3f -> currentColumns - 1
                        zoom < 0.77f -> currentColumns + 1
                        else -> currentColumns
                    }.coerceIn(AppPreferences.MIN_COLUMNS, AppPreferences.MAX_COLUMNS)
                    if (next != currentColumns) {
                        onChange(next)
                        zoom = 1f
                    }
                }
            } while (event.changes.any { it.pressed })
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun SelectionTopBar(
    count: Int,
    canDownload: Boolean,
    busy: Boolean,
    onClear: () -> Unit,
    onSelectAll: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    TopAppBar(
        navigationIcon = { IconButton(onClick = onClear) { Icon(Icons.Rounded.Close, "Clear selection") } },
        title = { Text("$count selected") },
        actions = {
            IconButton(onClick = onSelectAll, enabled = !busy) { Icon(Icons.Rounded.SelectAll, "Select all loaded") }
            if (canDownload) {
                IconButton(onClick = onDownload, enabled = !busy) { Icon(Icons.Rounded.Download, "Save cloud items to phone") }
            }
            IconButton(onClick = onDelete, enabled = !busy) { Icon(Icons.Rounded.Delete, "Delete selected") }
        },
        windowInsets = WindowInsets(0),
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    )
}

@Composable
private fun GridSizeMenu(columns: Int, onColumnsChange: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Rounded.GridView, "Grid size") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text(
                "Grid size · or pinch the grid",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            (AppPreferences.MIN_COLUMNS..AppPreferences.MAX_COLUMNS).forEach { count ->
                DropdownMenuItem(
                    text = { Text("$count per row") },
                    leadingIcon = { RadioButton(selected = count == columns, onClick = null) },
                    onClick = {
                        onColumnsChange(count)
                        open = false
                    },
                )
            }
        }
    }
}

@Composable
private fun FilterChipsRow(
    filters: PhotosFilters,
    enabled: Boolean,
    onSelectMediaFilter: (MediaFilter) -> Unit,
    onSelectLocationFilter: (BackupLocationFilter) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MediaFilter.entries.forEach { filter ->
            FilterChip(
                selected = filters.media == filter,
                onClick = { onSelectMediaFilter(filter) },
                enabled = enabled,
                label = { Text(mediaFilterLabel(filter)) },
            )
        }
        VerticalDivider(Modifier.height(24.dp))
        var open by remember { mutableStateOf(false) }
        Box {
            FilterChip(
                selected = filters.location != BackupLocationFilter.ALL,
                onClick = { open = true },
                enabled = enabled,
                label = { Text(locationFilterLabel(filters.location)) },
                trailingIcon = { Icon(Icons.Rounded.ExpandMore, null, Modifier.size(18.dp)) },
            )
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                BackupLocationFilter.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(locationFilterLabel(option)) },
                        leadingIcon = { RadioButton(selected = option == filters.location, onClick = null) },
                        onClick = {
                            onSelectLocationFilter(option)
                            open = false
                        },
                    )
                }
            }
        }
    }
}

private fun mediaFilterLabel(filter: MediaFilter) = when (filter) {
    MediaFilter.ALL -> "All"
    MediaFilter.PHOTOS -> "Photos"
    MediaFilter.VIDEOS -> "Videos"
}

private fun locationFilterLabel(filter: BackupLocationFilter) = when (filter) {
    BackupLocationFilter.ALL -> "Any backup status"
    BackupLocationFilter.PHONE_ONLY -> "Not backed up"
    BackupLocationFilter.CLOUD_ONLY -> "Only in cloud"
    BackupLocationFilter.PHONE_AND_CLOUD -> "Backed up"
}

@Composable
private fun DayHeader(label: String, selectionMode: Boolean, allSelected: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 14.dp, bottom = 4.dp).height(40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        AnimatedVisibility(selectionMode, enter = fadeIn(), exit = fadeOut()) {
            IconButton(onClick = onToggle) {
                Icon(
                    if (allSelected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                    if (allSelected) "Deselect this group" else "Select this group",
                    tint = if (allSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun OfflineBanner(onDetails: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.CloudOff, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text("Can’t reach your server", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Showing what’s on this phone. Backup resumes automatically.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onDetails) { Text("Details") }
        }
    }
}

@Composable
private fun PermissionCard(onRequest: () -> Unit) {
    Card(Modifier.padding(12.dp).fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Text("Allow access to all photos and videos", style = MaterialTheme.typography.titleMedium)
            Text(
                "Encrage needs full library access to back up new photos automatically. With selected access, only the items you pick are backed up.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            Button(onRequest) { Text("Allow access") }
        }
    }
}

@Composable
private fun EmptyGallery(filtered: Boolean, onClearFilters: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 72.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Rounded.PhotoLibrary, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            if (filtered) "Nothing matches these filters" else "No photos or videos yet",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            if (filtered) "Try a different type or backup status." else "New photos you take will appear here and back up automatically.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (filtered) TextButton(onClick = onClearFilters, modifier = Modifier.padding(top = 8.dp)) { Text("Clear filters") }
    }
}

@Composable
private fun MediaTilePlaceholder() {
    Box(Modifier.aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceContainerLow))
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun MediaTile(
    item: MediaEntity,
    selectionMode: Boolean,
    selected: Boolean,
    onLoadCloudThumbnail: suspend (String) -> Bitmap?,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val inset by animateFloatAsState(if (selected) 0.86f else 1f, label = "tileInset")
    val badge = statusBadge(item)
    Box(
        Modifier
            .aspectRatio(1f)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .semantics(mergeDescendants = true) {
                contentDescription = tileDescription(item, badge?.second)
                this.selected = selected
            }
            .combinedClickable(onClick = onTap, onLongClick = onLongPress),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = inset
                    scaleY = inset
                    clip = inset < 1f
                    shape = RoundedCornerShape(12.dp)
                }
                .background(MaterialTheme.colorScheme.surfaceContainer),
        ) {
            when {
                item.mediaStoreUri != null -> AsyncImage(
                    model = localTileModel(item.mediaStoreUri),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                item.mediaId != null -> CloudThumbnail(item.mediaId, onLoadCloudThumbnail, ContentScale.Crop, Modifier.fillMaxSize())
                else -> Icon(
                    Icons.Rounded.CloudOff, null,
                    modifier = Modifier.align(Alignment.Center),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (item.mediaType == MediaType.VIDEO) {
                Row(
                    Modifier.align(Alignment.BottomEnd).padding(5.dp).clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = .55f)).padding(horizontal = 4.dp, vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(14.dp))
                    item.durationSeconds?.let {
                        Text(formatDuration(it), color = Color.White, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            badge?.let { (icon, _) ->
                Box(
                    Modifier.align(Alignment.TopEnd).padding(5.dp).size(22.dp).clip(CircleShape)
                        .background(Color.Black.copy(alpha = .45f)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (icon == null) {
                        CircularProgressIndicator(Modifier.size(13.dp), color = Color.White, strokeWidth = 1.5.dp)
                    } else {
                        Icon(icon, null, tint = Color.White, modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
        if (selectionMode) {
            Icon(
                if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                null,
                tint = if (selected) MaterialTheme.colorScheme.primary else Color.White,
                modifier = Modifier.align(Alignment.TopStart).padding(5.dp).size(24.dp)
                    .then(if (selected) Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surface) else Modifier),
            )
        }
    }
}

/** Icon and spoken description for the tile's backup state; null icon means "in progress". Backed-up items show nothing. */
private fun statusBadge(item: MediaEntity): Pair<ImageVector?, String>? = when {
    item.localState == LocalState.CLOUD_ONLY -> Icons.Rounded.Cloud to "Only in cloud"
    item.localState != LocalState.LOCAL_ONLY -> null
    item.backupStatus == BackupStatus.FAILED -> Icons.Rounded.ErrorOutline to "Backup failed"
    item.backupStatus in IN_PROGRESS -> null to "Backing up"
    else -> Icons.Outlined.CloudUpload to "Waiting to back up"
}

private val IN_PROGRESS = setOf(BackupStatus.PREPARING, BackupStatus.ENCRYPTING, BackupStatus.UPLOADING, BackupStatus.DOWNLOADING)

private fun tileDescription(item: MediaEntity, status: String?): String {
    val kind = if (item.mediaType == MediaType.VIDEO) "Video" else "Photo"
    return listOfNotNull(kind, status ?: "Backed up").joinToString(", ")
}

private sealed interface CloudThumbnailUiState {
    data object Loading : CloudThumbnailUiState
    data object Unavailable : CloudThumbnailUiState
    data class Ready(val bitmap: Bitmap) : CloudThumbnailUiState
}

@Composable
internal fun CloudThumbnail(
    mediaId: String,
    onLoad: suspend (String) -> Bitmap?,
    contentScale: ContentScale,
    modifier: Modifier = Modifier,
    showProgress: Boolean = true,
) {
    val state by produceState<CloudThumbnailUiState>(CloudThumbnailUiState.Loading, mediaId) {
        value = onLoad(mediaId)?.let(CloudThumbnailUiState::Ready) ?: CloudThumbnailUiState.Unavailable
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        when (val current = state) {
            CloudThumbnailUiState.Loading -> if (showProgress) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.outline)
            }
            CloudThumbnailUiState.Unavailable -> Icon(Icons.Rounded.CloudOff, "Cloud thumbnail unavailable", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            is CloudThumbnailUiState.Ready -> Image(current.bitmap.asImageBitmap(), null, contentScale = contentScale, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
internal fun DeleteConfirmDialog(items: List<MediaEntity>, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val onPhone = items.count { it.mediaStoreUri != null }
    val inCloud = items.count { it.mediaId != null }
    val where = when {
        onPhone > 0 && inCloud > 0 -> "from this phone and from your Encrage cloud"
        onPhone > 0 -> "from this phone"
        else -> "from your Encrage cloud"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Delete, null) },
        title = { Text("Delete ${plural(items.size, "item")}?") },
        text = { Text("${if (items.size == 1) "It" else "They"} will be permanently removed $where. This can’t be undone.") },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) { Text("Delete") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Drag-to-scroll thumb that appears while scrolling and shows the month under the thumb, so long
 * libraries can be crossed in one gesture.
 */
@Composable
private fun BoxScope.FastScroller(state: LazyGridState, sections: List<DaySection>, gridStarts: IntArray) {
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    val totalItems by remember { derivedStateOf { state.layoutInfo.totalItemsCount } }
    val visibleItems by remember { derivedStateOf { state.layoutInfo.visibleItemsInfo.size } }
    val canScroll = totalItems > visibleItems * 3 && visibleItems > 0

    LaunchedEffect(state.isScrollInProgress, dragging, canScroll) {
        if (!canScroll) {
            visible = false
            return@LaunchedEffect
        }
        if (state.isScrollInProgress || dragging) visible = true else {
            delay(1200)
            visible = false
        }
    }
    AnimatedVisibility(
        visible = visible && canScroll,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
    ) {
        BoxWithConstraints(Modifier.fillMaxHeight().width(180.dp).padding(vertical = 8.dp)) {
            val trackHeight = maxHeight
            val thumbHeight = 48.dp
            val density = LocalDensity.current
            val thumbHalfPx = with(density) { (thumbHeight / 2).toPx() }
            val travelPx = with(density) { (trackHeight - thumbHeight).toPx() }.coerceAtLeast(1f)
            var dragFraction by remember { mutableStateOf<Float?>(null) }
            val firstVisible by remember { derivedStateOf { state.firstVisibleItemIndex } }
            val progress = dragFraction ?: (
                firstVisible.toFloat() / (totalItems - visibleItems).coerceAtLeast(1)
                ).coerceIn(0f, 1f)
            val anchorIndex = if (dragFraction != null) (progress * (totalItems - 1)).toInt() else firstVisible
            val sectionIndex = gridStarts.indexOfLastAtMost(anchorIndex)
            val label = sections.getOrNull(sectionIndex)?.day?.let(::monthLabel)

            // Thumb and date bubble (visual only).
            Row(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(y = (trackHeight - thumbHeight) * progress)
                    .height(thumbHeight),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (dragging && label != null) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.inverseSurface,
                        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                        shadowElevation = 4.dp,
                    ) {
                        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                }
                Box(
                    Modifier
                        .padding(end = 4.dp)
                        .width(if (dragging) 8.dp else 6.dp)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (dragging) .9f else .6f)),
                )
            }
            // Stationary touch track: positions are absolute, so moving the thumb never feeds back into the drag.
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .width(32.dp)
                    .fillMaxHeight()
                    .pointerInput(totalItems, visibleItems, travelPx) {
                        fun scrollTo(y: Float) {
                            val fraction = ((y - thumbHalfPx) / travelPx).coerceIn(0f, 1f)
                            dragFraction = fraction
                            val maxIndex = (totalItems - visibleItems).coerceAtLeast(0)
                            scope.launch { state.scrollToItem((fraction * maxIndex).toInt()) }
                        }
                        detectVerticalDragGestures(
                            onDragStart = { offset ->
                                dragging = true
                                scrollTo(offset.y)
                            },
                            onDragEnd = { dragging = false; dragFraction = null },
                            onDragCancel = { dragging = false; dragFraction = null },
                            onVerticalDrag = { change, _ ->
                                change.consume()
                                scrollTo(change.position.y)
                            },
                        )
                    },
            )
        }
    }
}

private fun IntArray.indexOfLastAtMost(value: Int): Int {
    var low = 0
    var high = size - 1
    var result = -1
    while (low <= high) {
        val mid = (low + high) ushr 1
        if (this[mid] <= value) {
            result = mid
            low = mid + 1
        } else high = mid - 1
    }
    return result
}
