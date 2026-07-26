package com.kickercam.ui

import android.util.Size
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.kickercam.capture.PreviewTarget

/**
 * The viewfinder: the camera's frames, at the shape the camera streams them in.
 *
 * A SurfaceView, as in Google's current Camera2 samples, and for one decisive reason: its buffer size
 * is whatever [SurfaceHolder.setFixedSize] says and nothing else touches it. TextureView re-sets the
 * buffer size to its own measured width and height on every layout, so the camera ended up streaming
 * at a size the app never asked for and the device never advertised — the HAL then substitutes
 * whatever it does have, and a stream of one shape drawn into a view of another is the stretch that
 * would not go away.
 *
 * There is no rotation and no transform here. The view is given the stream's aspect ratio, so filling
 * it is a uniform scale; whatever the frames do not cover is left black by the caller.
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
            // The only geometry in the viewfinder. Not fillMaxSize as well: fixed constraints leave
            // aspectRatio nothing to choose and it gives up silently, which stretched the preview
            // across the whole screen.
            modifier = Modifier.aspectRatio(aspectRatioOf(bufferSize)),
            factory = { context ->
                SurfaceView(context).apply {
                    this.holder.addCallback(holder)
                }
            },
        )
    }
}

private class PreviewSurfaceHolder(
    var callback: (PreviewTarget?) -> Unit,
) : SurfaceHolder.Callback {

    private var surfaceHolder: SurfaceHolder? = null
    private var streamSize: Size? = null

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceHolder = holder
        callback(
            PreviewTarget(holder.surface) { size ->
                streamSize = size
                applyFixedSize()
            },
        )
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceHolder = null
        callback(null)
    }

    /** Pins the surface's buffers to the size the capture engine chose, whatever the view measures. */
    private fun applyFixedSize() {
        val size = streamSize ?: return
        val holder = surfaceHolder ?: return
        runCatching { holder.setFixedSize(size.width, size.height) }
    }

    fun release() {
        surfaceHolder = null
        callback(null)
    }
}

/** Shape of the camera's frames. Falls back to 16:9 before the camera has reported a format. */
fun aspectRatioOf(widthPx: Int, heightPx: Int): Float =
    if (widthPx > 0 && heightPx > 0) widthPx.toFloat() / heightPx.toFloat() else 16f / 9f

fun aspectRatioOf(size: Size): Float = aspectRatioOf(size.width, size.height)
