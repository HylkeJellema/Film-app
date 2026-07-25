package com.kickercam.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.kickercam.storage.Clip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Decoded thumbnail strips are small; keeping a few around makes gallery scrolling smooth. */
private object ThumbnailCache {
    private val cache = object : LruCache<String, List<ImageBitmap>>(24) {}

    fun get(id: String): List<ImageBitmap>? = cache.get(id)

    fun put(id: String, frames: List<ImageBitmap>) {
        if (frames.isNotEmpty()) cache.put(id, frames)
    }

    fun evict(id: String) {
        cache.remove(id)
    }
}

/**
 * The low-res looping preview of the detection moment.
 *
 * Scrubbing a grid of these is how you find the one good hit among twenty clips without opening any
 * of them.
 */
@Composable
fun AnimatedClipThumbnail(
    clip: Clip,
    playing: Boolean,
    modifier: Modifier = Modifier,
    frameDurationMs: Long = 110L,
) {
    var frames by remember(clip.id) { mutableStateOf(ThumbnailCache.get(clip.id) ?: emptyList()) }
    var index by remember(clip.id) { mutableIntStateOf(0) }

    LaunchedEffect(clip.id) {
        if (frames.isEmpty()) {
            val loaded = withContext(Dispatchers.IO) {
                clip.thumbnailFrames.mapNotNull { file ->
                    runCatching { BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap() }.getOrNull()
                }
            }
            ThumbnailCache.put(clip.id, loaded)
            frames = loaded
        }
    }

    LaunchedEffect(playing, frames.size) {
        if (!playing || frames.size < 2) {
            index = 0
            return@LaunchedEffect
        }
        while (true) {
            delay(frameDurationMs)
            index = (index + 1) % frames.size
        }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        when {
            frames.isNotEmpty() -> Image(
                bitmap = frames[index.coerceIn(0, frames.size - 1)],
                contentDescription = "Detection moment preview",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )

            clip.meta.thumbFrameCount == 0 -> Text(
                text = "no preview",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            else -> CircularProgressIndicator(strokeWidth = androidx.compose.ui.unit.Dp(2f))
        }
    }
}

/** Call after deleting a clip so its frames are not held in memory. */
fun evictThumbnailCache(clipId: String) = ThumbnailCache.evict(clipId)
