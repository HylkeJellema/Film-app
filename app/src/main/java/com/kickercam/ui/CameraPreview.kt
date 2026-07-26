package com.kickercam.ui

import android.graphics.SurfaceTexture
import android.util.Size
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.kickercam.capture.PreviewTarget

/**
 * The viewfinder: the camera's frames, at their own shape, as large as they fit.
 *
 * There is no rotation anywhere in here, and no transform. The app is landscape and the camera hands
 * over landscape frames, so the only thing that has to be right is the shape of the view: give a
 * TextureView the buffer's aspect ratio and its default behaviour — filling itself with the buffer —
 * is already a uniform scale. This is what `AutoFitTextureView` does in Google's Camera2 samples, and
 * every attempt here to be cleverer than it (matrices, rotated containers, transposed slots) managed
 * only to stretch, square off or turn the picture.
 *
 * Whatever the frames do not cover is left black by the caller.
 */
@Composable
fun CameraPreview(
    bufferSize: Size,
    onTargetChanged: (PreviewTarget?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val holder = remember { PreviewSurfaceHolder(onTargetChanged) }
    holder.callback = onTargetChanged

    DisposableEffect(Unit) {
        onDispose { holder.release() }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        AndroidView(
            // The one piece of geometry in the viewfinder. Without fillMaxSize: fixed constraints
            // leave aspectRatio nothing to choose and it silently gives up, which is how the preview
            // came to be stretched across the whole screen.
            modifier = Modifier.aspectRatio(bufferAspectRatio(bufferSize)),
            factory = { context ->
                TextureView(context).apply {
                    isOpaque = true
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(
                            texture: SurfaceTexture,
                            width: Int,
                            height: Int,
                        ) = holder.onAvailable(texture)

                        override fun onSurfaceTextureSizeChanged(
                            texture: SurfaceTexture,
                            width: Int,
                            height: Int,
                        ) = holder.reassertBufferSize()

                        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                            holder.release()
                            return true
                        }

                        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                    }
                }
            },
        )
    }
}

private class PreviewSurfaceHolder(var callback: (PreviewTarget?) -> Unit) {

    private var texture: SurfaceTexture? = null
    private var surface: Surface? = null

    /** The size the capture engine asked the camera to stream at. */
    private var streamSize: Size? = null

    fun onAvailable(texture: SurfaceTexture) {
        this.texture = texture
        val created = Surface(texture)
        surface = created
        callback(
            PreviewTarget(created) { size ->
                streamSize = size
                applyBufferSize()
            },
        )
    }

    /**
     * Re-asserts the engine's buffer size, which TextureView overwrites with its own.
     *
     * TextureView calls `setDefaultBufferSize(getWidth(), getHeight())` whenever it is laid out, which
     * leaves the camera streaming at a size it never advertised; the HAL then substitutes whatever it
     * does have, typically the sensor's native 4:3, and a 4:3 stream in a 16:9 view is stretched by a
     * third. The engine's choice has to be the last word.
     */
    fun reassertBufferSize() = applyBufferSize()

    private fun applyBufferSize() {
        val size = streamSize ?: return
        val target = texture ?: return
        runCatching { target.setDefaultBufferSize(size.width, size.height) }
    }

    fun release() {
        if (surface == null && texture == null) return
        callback(null)
        surface?.release()
        surface = null
        texture = null
    }
}

/** Shape of the camera's frames. Falls back to 16:9 before the camera has reported a format. */
fun bufferAspectRatio(widthPx: Int, heightPx: Int): Float =
    if (widthPx > 0 && heightPx > 0) widthPx.toFloat() / heightPx.toFloat() else 16f / 9f

fun bufferAspectRatio(size: Size): Float = bufferAspectRatio(size.width, size.height)
