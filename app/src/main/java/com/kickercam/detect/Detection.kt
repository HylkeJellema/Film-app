package com.kickercam.detect

import com.kickercam.settings.RoiRect

/** What a detector concluded about one analysis frame. */
data class DetectionOutcome(
    val hit: Boolean = false,
    val confidence: Float = 0f,
    /** Boxes in preview display space, for the overlay. */
    val boxes: List<RoiRect> = emptyList(),
    /** 0..1 activity level inside the box, drives the on-screen meter. */
    val roiEnergy: Float = 0f,
    /** 0..1 activity level outside the box; subtracted to reject wind, waves and shake. */
    val backgroundEnergy: Float = 0f,
    val label: String? = null,
)

/**
 * Converts normalised rectangles between the analysis image frame (always sensor orientation) and
 * the preview display frame (the image rotated by [rotationDegrees]).
 */
object RoiMapper {

    fun displayToImage(rect: RoiRect, rotationDegrees: Int): RoiRect = when (normalise(rotationDegrees)) {
        90 -> RoiRect(rect.top, 1f - rect.right, rect.height, rect.width)
        180 -> RoiRect(1f - rect.right, 1f - rect.bottom, rect.width, rect.height)
        270 -> RoiRect(1f - rect.bottom, rect.left, rect.height, rect.width)
        else -> rect
    }

    fun imageToDisplay(rect: RoiRect, rotationDegrees: Int): RoiRect = when (normalise(rotationDegrees)) {
        90 -> RoiRect(1f - rect.bottom, rect.left, rect.height, rect.width)
        180 -> RoiRect(1f - rect.right, 1f - rect.bottom, rect.width, rect.height)
        270 -> RoiRect(rect.top, 1f - rect.right, rect.height, rect.width)
        else -> rect
    }

    private fun normalise(degrees: Int): Int = ((degrees % 360) + 360) % 360
}

/** Linear interpolation helper used to turn the single sensitivity slider into per-detector thresholds. */
internal fun lerp(from: Float, to: Float, t: Float): Float = from + (to - from) * t.coerceIn(0f, 1f)
