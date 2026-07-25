package com.kickercam.ui

import android.graphics.SurfaceTexture
import android.util.Size
import android.view.Surface
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
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
 * The buffer size is deliberately *not* set here: the capture engine sets it through
 * [PreviewTarget] right before it configures the session, which is the only point where the final
 * recording resolution is known.
 */
@Composable
fun CameraPreview(
    rotationDegrees: Int,
    onTargetChanged: (PreviewTarget?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val holder = remember { PreviewSurfaceHolder(onTargetChanged) }
    holder.callback = onTargetChanged

    DisposableEffect(Unit) {
        onDispose { holder.release() }
    }

    AndroidView(
        modifier = modifier.graphicsLayer {
            // Only half turns are possible while the activity is locked to landscape; a quarter turn
            // would also need the view bounds swapped, so it is deliberately ignored here.
            rotationZ = if (rotationDegrees == 180) 180f else 0f
        },
        factory = { context ->
            TextureView(context).apply {
                isOpaque = true
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                        holder.onAvailable(texture)
                    }

                    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit

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

/** Aspect ratio to lay the viewfinder out with, so the preview matches what lands in the file. */
fun previewAspectRatio(size: Size): Float =
    if (size.height > 0) size.width.toFloat() / size.height.toFloat() else 16f / 9f
