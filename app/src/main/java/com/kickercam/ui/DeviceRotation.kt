package com.kickercam.ui

import android.view.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * How far the window is rotated from the device's natural orientation, in degrees.
 *
 * This is the *window's* rotation, not the phone's attitude in space, and the distinction is the
 * whole point. Whether the viewfinder looks right is a question about screen space: the image has to
 * line up with the UI drawn around it. Gravity is a different question, and answering it instead is
 * what put the preview on its side — with the window locked to landscape and the image turned upright
 * against gravity, holding the phone portrait produced a sideways image in a narrow strip, which is
 * exactly what it was asked for and not at all what anyone wanted.
 *
 * The activity now rotates with the device, so the window follows how the phone is held and the two
 * questions have the same answer again. In portrait the window reports its natural orientation and no
 * rotation is applied at all.
 */
@Composable
fun rememberWindowRotationDegrees(): Int {
    val context = LocalContext.current

    // The activity handles configuration changes itself, so this is what recomposes on a rotation.
    LocalConfiguration.current

    return surfaceRotationToDegrees(ContextCompat.getDisplayOrDefault(context).rotation)
}

fun surfaceRotationToDegrees(surfaceRotation: Int): Int = when (surfaceRotation) {
    Surface.ROTATION_90 -> 90
    Surface.ROTATION_180 -> 180
    Surface.ROTATION_270 -> 270
    else -> 0
}
