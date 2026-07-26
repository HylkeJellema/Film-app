package com.kickercam.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.kickercam.ui.theme.KickerOrange
import com.kickercam.vm.CameraViewModel

// media3-ui is opt-in via an androidx annotation, which needs androidx's OptIn, not Kotlin's.
@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    viewModel: CameraViewModel,
    clipId: String,
    onBack: () -> Unit,
) {
    val clips by viewModel.clips.collectAsStateWithLifecycle()
    val clip = remember(clips, clipId) { clips.firstOrNull { it.id == clipId } }
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val exportMessage by viewModel.exportMessage.collectAsStateWithLifecycle()

    var speed by remember { mutableFloatStateOf(1f) }

    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_ONE
            playWhenReady = true
        }
    }

    DisposableEffect(clip?.videoFile?.absolutePath) {
        clip?.let {
            player.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(it.videoFile)))
            player.prepare()
            // Open on the action rather than at the start of the pre-roll.
            player.seekTo(it.meta.momentOffsetMs.coerceAtLeast(0L))
        }
        onDispose { }
    }

    DisposableEffect(Unit) {
        onDispose { player.release() }
    }

    LaunchedEffect(speed) { player.setPlaybackSpeed(speed) }

    LaunchedEffect(exportMessage) {
        exportMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearExportMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(clip?.let { "${it.meta.durationMs / 1000}s clip" } ?: "Clip") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    clip?.let { current ->
                        IconButton(onClick = { share(context, current.videoFile) }) {
                            Icon(Icons.Filled.Share, contentDescription = "Share")
                        }
                        IconButton(onClick = {
                            evictThumbnailCache(current.id)
                            viewModel.deleteClip(current)
                            onBack()
                        }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete")
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (clip == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Clip no longer exists")
            }
            return@Scaffold
        }

        Column(Modifier.fillMaxSize().padding(padding).background(Color.Black)) {
            AndroidView(
                modifier = Modifier.fillMaxWidth().weight(1f),
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        useController = true
                        setShowNextButton(false)
                        setShowPreviousButton(false)
                    }
                },
            )

            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Playback speed",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                    Spacer(Modifier.width(10.dp))
                    for (option in listOf(0.25f, 0.5f, 1f, 2f)) {
                        FilterChip(
                            selected = speed == option,
                            onClick = { speed = option },
                            label = { Text("${option}x", style = MaterialTheme.typography.labelSmall) },
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                }

                Spacer(Modifier.height(10.dp))

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(onClick = { player.seekTo(clip.meta.momentOffsetMs) }) {
                        Icon(Icons.Filled.MyLocation, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Jump to moment")
                    }

                    // Always enabled: an earlier export says nothing about whether the file is
                    // still in the gallery, and re-exporting a clip you deleted by accident should
                    // not require deleting and re-shooting it.
                    Button(onClick = { viewModel.exportClip(clip) }) {
                        Icon(Icons.Filled.Download, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (clip.meta.exported) "Save again" else "Save to gallery")
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Moment at ${clip.meta.momentOffsetMs / 1000f}s · " +
                        "${clip.meta.widthPx}x${clip.meta.heightPx} @ ${clip.meta.fps}fps · " +
                        "${clip.meta.lensLabel} · trigger: ${clip.meta.triggerReason}" +
                        if (clip.meta.hasAudio) " · audio" else " · no audio",
                    style = MaterialTheme.typography.labelSmall,
                    color = KickerOrange,
                )
            }
        }
    }
}

private fun share(context: android.content.Context, file: java.io.File) {
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share clip"))
    }
}
