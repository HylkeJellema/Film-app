package com.kickercam.ui

import android.graphics.SurfaceTexture
import android.util.Size
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
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

        Box(
            modifier = Modifier
                .size(width = viewWidth, height = viewHeight)
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
                            ) = Unit

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

    fun onAvailable(texture: SurfaceTexture) {
        this.texture = texture
        val created = Surface(texture)
        surface = created
        callback(
            PreviewTarget(created) { size ->
                runCatching { texture.setDefaultBufferSize(size.width, size.height) }
            },
        )
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
