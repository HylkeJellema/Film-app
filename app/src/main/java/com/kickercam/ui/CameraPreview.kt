package com.kickercam.ui

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.util.Size
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.kickercam.capture.PreviewTarget

/**
 * The viewfinder: one TextureView filling the whole area, with one matrix placing the camera image
 * inside it.
 *
 * Earlier versions tried to express this through layout — an aspect-ratio box, a view laid out at
 * buffer proportions, a rotated container whose width and height were swapped. Every one of those
 * pieces was another chance to get it wrong, and between constraint clamping and a stale buffer size
 * they took most of those chances. So none of it is laid out any more: the view is simply the full
 * area, and where the picture goes within it is [previewFit]'s single answer, applied as a transform.
 *
 * The scale is one number for both axes, so the image cannot be stretched. What it does not cover
 * stays black.
 */
@Composable
fun CameraPreview(
    bufferSize: Size,
    rotationDegrees: Int,
    onTargetChanged: (PreviewTarget?) -> Unit,
    onFitChanged: (PreviewFit?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val holder = remember { PreviewSurfaceHolder(onTargetChanged) }
    holder.callback = onTargetChanged
    holder.onFit = onFitChanged
    holder.bufferSize = bufferSize
    holder.rotationDegrees = rotationDegrees

    DisposableEffect(Unit) {
        onDispose { holder.release() }
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            TextureView(context).apply {
                isOpaque = false
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(
                        texture: SurfaceTexture,
                        width: Int,
                        height: Int,
                    ) = holder.onAvailable(this@apply, texture)

                    override fun onSurfaceTextureSizeChanged(
                        texture: SurfaceTexture,
                        width: Int,
                        height: Int,
                    ) = holder.onViewResized(this@apply)

                    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                        holder.release()
                        return true
                    }

                    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                }
            }
        },
        // Runs on every recomposition, which is what re-places the image when the format or the
        // rotation changes without waiting for a resize.
        update = { view -> holder.apply(view) },
    )
}

private class PreviewSurfaceHolder(var callback: (PreviewTarget?) -> Unit) {

    var onFit: (PreviewFit?) -> Unit = {}
    var bufferSize: Size = Size(0, 0)
    var rotationDegrees: Int = 0

    private var texture: SurfaceTexture? = null
    private var surface: Surface? = null

    /** The size the capture engine asked the camera to stream at. */
    private var streamSize: Size? = null

    private var reported: PreviewFit? = null

    fun onAvailable(view: TextureView, texture: SurfaceTexture) {
        this.texture = texture
        val created = Surface(texture)
        surface = created
        callback(
            PreviewTarget(created) { size ->
                streamSize = size
                applyBufferSize()
                view.post { apply(view) }
            },
        )
    }

    fun onViewResized(view: TextureView) {
        // TextureView resets the buffer size to its own dimensions whenever it is laid out, which
        // leaves the camera streaming at a size it never advertised — and the HAL then substitutes
        // whatever it does have, typically the sensor's native 4:3. The engine's choice has to win.
        applyBufferSize()
        apply(view)
    }

    /**
     * Places the camera image inside the view.
     *
     * TextureView's default is to stretch the buffer across the whole view, so the transform starts by
     * undoing that stretch, then rotates about the image's centre, scales both axes by the one factor
     * [previewFit] chose, and centres the result.
     */
    fun apply(view: TextureView) {
        val bufferWidth = bufferSize.width
        val bufferHeight = bufferSize.height
        val viewWidth = view.width.toFloat()
        val viewHeight = view.height.toFloat()

        val fit = previewFit(viewWidth, viewHeight, bufferWidth, bufferHeight, rotationDegrees)
        if (fit == null) {
            report(null)
            return
        }

        val matrix = Matrix().apply {
            setScale(bufferWidth / viewWidth, bufferHeight / viewHeight)
            postTranslate(-bufferWidth / 2f, -bufferHeight / 2f)
            postRotate(normaliseRotation(rotationDegrees).toFloat())
            postScale(fit.scale, fit.scale)
            postTranslate(viewWidth / 2f, viewHeight / 2f)
        }
        view.setTransform(matrix)
        view.invalidate()
        report(fit)
    }

    private fun report(fit: PreviewFit?) {
        if (fit == reported) return
        reported = fit
        onFit(fit)
    }

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
        report(null)
    }
}
