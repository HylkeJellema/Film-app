package com.kickercam.capture

import android.util.Size
import android.view.Surface

/**
 * The viewfinder surface plus the ability to resize its buffers.
 *
 * Camera2 derives a stream's resolution from the surface it is handed, so whoever decides the
 * recording format has to set the preview buffer size *before* configuring the session. Bundling the
 * two together lets [CaptureEngine] do that itself instead of hoping the UI recomposed in time.
 */
class PreviewTarget(
    val surface: Surface,
    private val applyBufferSize: (Size) -> Unit,
) {
    fun setBufferSize(size: Size) {
        applyBufferSize(size)
    }
}
