package com.kickercam.detect

import com.kickercam.settings.RoiRect

/** What a detector concluded about one analysis frame. */
data class DetectionOutcome(
    val hit: Boolean = false,
    val confidence: Float = 0f,
    /** Boxes in frame coordinates, which is also viewfinder space, for the overlay. */
    val boxes: List<RoiRect> = emptyList(),
    /** 0..1 activity level inside the box, drives the on-screen meter. */
    val roiEnergy: Float = 0f,
    /** 0..1 activity level outside the box; subtracted to reject wind, waves and shake. */
    val backgroundEnergy: Float = 0f,
    val label: String? = null,
)

/** Linear interpolation helper used to turn the single sensitivity slider into per-detector thresholds. */
internal fun lerp(from: Float, to: Float, t: Float): Float = from + (to - from) * t.coerceIn(0f, 1f)
