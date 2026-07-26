package com.kickercam.ui

import android.graphics.SurfaceTexture
import android.util.Size
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.viewinterop.AndroidView
import com.kickercam.capture.PreviewTarget

/**
 * TextureView-backed viewfinder.
 *
 * A TextureView rather than a SurfaceView because the camera always delivers frames in sensor
 * orientation and the view has to be rotated to compensate — TextureView composites through the
 * normal view hierarchy, so rotating it is well defined.
 *
 * All four rotations are handled. A quarter turn also swaps the view's width and height, because a
 * 16:9 view rotated 90° occupies a 9:16 slot on screen; laying the view out at buffer proportions
 * and then rotating it into place is what keeps the image square with the world instead of stretched.
 *
 * The buffer size is deliberately not set here: the capture engine sets it through [PreviewTarget]
 * right before it configures the session, which is the only point where the final recording
 * resolution is known.
 */
@Composable
fun CameraPreview(
    bufferSize: Size,
    rotationDegrees: Int,
    onTargetChanged: (PreviewTarget?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val holder = remember { PreviewSurfaceHolder(onTargetChanged) }
    holder.callback = onTargetChanged

    DisposableEffect(Unit) {
        onDispose { holder.release() }
    }

    val quarterTurn = isQuarterTurn(rotationDegrees)
    val bufferAspect = bufferAspectRatio(bufferSize)

    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        // The view is laid out at the buffer's own proportions, then rotated into the slot.
        val viewWidth = if (quarterTurn) maxHeight else maxWidth
        val viewHeight = if (quarterTurn) maxWidth else maxHeight

        // The overwhelmingly common case on a tripod is no rotation at all, so it gets no graphics
        // layer — a rotated TextureView is the fiddly path and it should not be on the happy path.
        val rotation = ((rotationDegrees % 360) + 360) % 360
        val rotationModifier = if (rotation == 0) {
            Modifier
        } else {
            Modifier.graphicsLayer { rotationZ = rotation.toFloat() }
        }

        // requiredSize, not size: a quarter turn asks for a box wider than the slot it will occupy
        // once rotated, and size() is clamped by the incoming constraints. Asking for 2560x1440 inside
        // a 1440x2560 slot got clamped to 1440x1440 — an actual square, with the image shrunk to fit
        // inside it while the overlay still spanned the full slot. requiredSize ignores the clamp,
        // which is exactly right here: after the rotation the box lands inside the slot anyway.
        Box(
            modifier = Modifier
                .requiredSize(width = viewWidth, height = viewHeight)
                .then(rotationModifier),
        ) {
            AndroidView(
                modifier = Modifier.aspectRatio(bufferAspect).align(Alignment.Center),
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
}

private class PreviewSurfaceHolder(var callback: (PreviewTarget?) -> Unit) {

    private var texture: SurfaceTexture? = null
    private var surface: Surface? = null

    /** What the capture engine asked for, kept so it can be re-applied. */
    private var requestedSize: Size? = null

    fun onAvailable(texture: SurfaceTexture) {
        this.texture = texture
        val created = Surface(texture)
        surface = created
        callback(
            PreviewTarget(created) { size ->
                requestedSize = size
                applyBufferSize()
            },
        )
    }

    /**
     * Re-asserts the engine's buffer size, which TextureView overwrites on its own.
     *
     * TextureView calls `setDefaultBufferSize(getWidth(), getHeight())` whenever it is laid out. The
     * camera then streams at whatever the *view* happened to measure — not a size it advertised, so
     * the HAL substitutes the nearest one it has, typically the sensor's native 4:3. That is what a
     * 16:9 slot showing a 4:3 stream looks like: a 16:9 TV squashed towards square. The engine sets
     * the size once before configuring the session, so every later layout has to be undone.
     */
    fun reassertBufferSize() = applyBufferSize()

    private fun applyBufferSize() {
        val size = requestedSize ?: return
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

fun isQuarterTurn(rotationDegrees: Int): Boolean {
    val normalised = ((rotationDegrees % 360) + 360) % 360
    return normalised == 90 || normalised == 270
}

/** Aspect ratio of the camera buffer itself, always in sensor (landscape) orientation. */
fun bufferAspectRatio(widthPx: Int, heightPx: Int): Float =
    if (heightPx > 0 && widthPx > 0) widthPx.toFloat() / heightPx.toFloat() else 16f / 9f

fun bufferAspectRatio(size: Size): Float = bufferAspectRatio(size.width, size.height)

/**
 * Aspect ratio the preview occupies on screen once [rotationDegrees] has been applied. A quarter
 * turn transposes it.
 */
fun previewAspectRatio(widthPx: Int, heightPx: Int, rotationDegrees: Int): Float {
    val bufferAspect = bufferAspectRatio(widthPx, heightPx)
    return if (isQuarterTurn(rotationDegrees)) 1f / bufferAspect else bufferAspect
}

fun previewAspectRatio(size: Size, rotationDegrees: Int): Float =
    previewAspectRatio(size.width, size.height, rotationDegrees)
