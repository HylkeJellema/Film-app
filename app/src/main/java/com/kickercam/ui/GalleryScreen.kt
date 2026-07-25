package com.kickercam.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kickercam.storage.Clip
import com.kickercam.ui.theme.KickerGreen
import com.kickercam.ui.theme.KickerOrange
import com.kickercam.vm.CameraViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun GalleryScreen(
    viewModel: CameraViewModel,
    onBack: () -> Unit,
    onOpenClip: (String) -> Unit,
) {
    val clips by viewModel.clips.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<Clip?>(null) }

    LaunchedEffect(Unit) { viewModel.refreshClips() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Clips (${clips.size})") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refreshClips() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                },
            )
        },
    ) { padding ->
        if (clips.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No clips yet", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Point the box at the kicker, arm the camera and go ride.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            return@Scaffold
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 200.dp),
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(clips, key = { it.id }) { clip ->
                ClipCard(
                    clip = clip,
                    onClick = { onOpenClip(clip.id) },
                    onLongClick = { pendingDelete = clip },
                )
            }
        }
    }

    pendingDelete?.let { clip ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete this clip?") },
            text = { Text("${clip.meta.durationMs / 1000}s · ${clip.sizeBytes / (1024 * 1024)} MB") },
            confirmButton = {
                TextButton(onClick = {
                    evictThumbnailCache(clip.id)
                    viewModel.deleteClip(clip)
                    pendingDelete = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ClipCard(
    clip: Clip,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val aspect = remember(clip.id) {
        if (clip.meta.heightPx > 0) {
            clip.meta.widthPx.toFloat() / clip.meta.heightPx.toFloat()
        } else {
            16f / 9f
        }
    }

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box {
            AnimatedClipThumbnail(
                clip = clip,
                playing = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(aspect)
                    .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                    .background(Color.Black),
            )

            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (clip.meta.exported) {
                    Icon(
                        Icons.Filled.CloudDone,
                        contentDescription = "Exported",
                        tint = KickerGreen,
                        modifier = Modifier.height(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    text = "${clip.meta.durationMs / 1000}s",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }

        Column(Modifier.padding(10.dp)) {
            Text(
                text = formatClipTime(clip.meta.createdAtEpochMs),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "${clip.meta.widthPx}x${clip.meta.heightPx} · ${clip.meta.fps}fps · " +
                    "${clip.sizeBytes / (1024 * 1024)} MB",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "${clip.meta.lensLabel} · ${clip.meta.triggerReason}",
                style = MaterialTheme.typography.labelSmall,
                color = KickerOrange,
                maxLines = 1,
            )
        }
    }
}

/** Built per call so it follows a locale change instead of freezing the one at class-init. */
private fun formatClipTime(epochMs: Long): String =
    SimpleDateFormat("EEE d MMM · HH:mm:ss", Locale.getDefault()).format(Date(epochMs))
