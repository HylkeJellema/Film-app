package com.kickercam.ui

import android.view.OrientationEventListener
import android.view.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * The device's physical orientation, in degrees, snapped to the nearest quarter turn.
 *
 * This deliberately does not use `Display.getRotation()`. The viewfinder activity is locked to
 * landscape, so the display never actually rotates — it keeps reporting the natural orientation while
 * the window is drawn landscape. Feeding that into the sensor-orientation formula yields a spurious
 * quarter turn, which is exactly what left the preview lying on its side. The accelerometer is the
 * only honest source of "which way up is the phone" for a fixed-orientation activity.
 */
@Composable
fun rememberDeviceRotationDegrees(): Int {
    val context = LocalContext.current

    // A landscape-locked window is always at a quarter turn; start there rather than at 0 so the
    // very first frames are already oriented correctly.
    var rotationDegrees by remember { mutableIntStateOf(90) }

    DisposableEffect(context) {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val surfaceRotation = when {
                    orientation >= 45 && orientation < 135 -> Surface.ROTATION_270
                    orientation >= 135 && orientation < 225 -> Surface.ROTATION_180
                    orientation >= 225 && orientation < 315 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
                val degrees = surfaceRotationToDegrees(surfaceRotation)
                if (degrees != rotationDegrees) rotationDegrees = degrees
            }
        }
        if (listener.canDetectOrientation()) listener.enable()
        onDispose { listener.disable() }
    }

    return rotationDegrees
}

fun surfaceRotationToDegrees(surfaceRotation: Int): Int = when (surfaceRotation) {
    Surface.ROTATION_90 -> 90
    Surface.ROTATION_180 -> 180
    Surface.ROTATION_270 -> 270
    else -> 0
}
