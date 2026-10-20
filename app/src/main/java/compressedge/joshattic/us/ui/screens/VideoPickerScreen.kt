package compressedge.joshattic.us.ui.screens

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import compressedge.joshattic.us.R
import compressedge.joshattic.us.picker.VideoLibrary
import compressedge.joshattic.us.picker.VideoLibraryItem
import compressedge.joshattic.us.picker.VideoSort
import compressedge.joshattic.us.picker.VideoThumbnailCache
import compressedge.joshattic.us.ui.components.SelectionChip
import compressedge.joshattic.us.utils.formatFileSize
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Built-in video picker backed by MediaStore: sortable, multi-select, and
 * already-compressed outputs (the "Compressor Edge" folder) are hidden by
 * the query in [VideoLibrary].
 */
@Composable
fun VideoPickerScreen(
    onConfirm: (List<Uri>) -> Unit,
    onCancel: () -> Unit,
    onUseSystemPicker: () -> Unit
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current

    var sort by remember { mutableStateOf(VideoSort.NEWEST) }
    var items by remember { mutableStateOf<List<VideoLibraryItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    // Ids in tap order; the number badge on each thumbnail shows this order.
    var selectedIds by remember { mutableStateOf<List<Long>>(emptyList()) }

    LaunchedEffect(sort) {
        isLoading = true
        items = withContext(Dispatchers.IO) { VideoLibrary.loadVideos(context, sort) }
        isLoading = false
    }

    fun toggle(item: VideoLibraryItem) {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        selectedIds = if (selectedIds.contains(item.id)) {
            selectedIds - item.id
        } else {
            selectedIds + item.id
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onCancel) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.picker_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                if (!isLoading) {
                    Text(
                        text = stringResource(R.string.picker_count, items.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            VideoSort.entries.forEach { option ->
                SelectionChip(
                    selected = sort == option,
                    onClick = { sort = option },
                    label = stringResource(
                        when (option) {
                            VideoSort.NEWEST -> R.string.picker_sort_newest
                            VideoSort.OLDEST -> R.string.picker_sort_oldest
                            VideoSort.LARGEST -> R.string.picker_sort_largest
                            VideoSort.SMALLEST -> R.string.picker_sort_smallest
                            VideoSort.LONGEST -> R.string.picker_sort_longest
                            VideoSort.NAME -> R.string.picker_sort_name
                        }
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            when {
                isLoading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                items.isEmpty() -> {
                    Text(
                        text = stringResource(R.string.picker_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 108.dp),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(items, key = { it.id }) { item ->
                            VideoGridItem(
                                item = item,
                                selectionIndex = selectedIds.indexOf(item.id) + 1,
                                onToggle = { toggle(item) }
                            )
                        }
                    }
                }
            }
        }

        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onUseSystemPicker) {
                    Text(stringResource(R.string.picker_use_system))
                }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.cancel))
                }
                Spacer(modifier = Modifier.width(4.dp))
                Button(
                    onClick = {
                        val byId = items.associateBy { it.id }
                        onConfirm(selectedIds.mapNotNull { byId[it]?.uri })
                    },
                    enabled = selectedIds.isNotEmpty()
                ) {
                    Text(stringResource(R.string.picker_add_count, selectedIds.size))
                }
            }
        }
    }
}

@Composable
private fun VideoGridItem(
    item: VideoLibraryItem,
    selectionIndex: Int,
    onToggle: () -> Unit
) {
    val context = LocalContext.current
    var thumbnail by remember(item.id) { mutableStateOf(VideoThumbnailCache.get(item.id)) }

    LaunchedEffect(item.id) {
        if (thumbnail == null) {
            val loaded = withContext(Dispatchers.IO) { VideoLibrary.loadThumbnail(context, item) }
            if (loaded != null) {
                VideoThumbnailCache.put(item.id, loaded)
                thumbnail = loaded
            }
        }
    }

    val selected = selectionIndex > 0

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable { onToggle() }
    ) {
        val bmp = thumbnail
        if (bmp != null) {
            androidx.compose.foundation.Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = Icons.Default.Movie,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(32.dp)
                    .align(Alignment.Center)
            )
        }

        // Bottom info strip: duration + size
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f))
                    )
                )
                .padding(horizontal = 6.dp, vertical = 4.dp)
        ) {
            Text(
                text = formatDuration(item.durationMs),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.align(Alignment.BottomStart)
            )
            Text(
                text = formatFileSize(item.sizeBytes),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.align(Alignment.BottomEnd)
            )
        }

        if (selected) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(24.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = selectionIndex.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }
    }
}

private fun formatDuration(durationMs: Long): String {
    if (durationMs <= 0) return "0:00"
    val totalSeconds = durationMs / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}
