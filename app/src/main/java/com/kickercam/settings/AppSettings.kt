package com.kickercam.settings

/** Normalised rectangle (0..1) in preview display space. */
data class RoiRect(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height
    val centerX: Float get() = left + width / 2f
    val centerY: Float get() = top + height / 2f

    fun clampToUnit(minSize: Float = 0.06f): RoiRect {
        val w = width.coerceIn(minSize, 1f)
        val h = height.coerceIn(minSize, 1f)
        return RoiRect(
            left = left.coerceIn(0f, 1f - w),
            top = top.coerceIn(0f, 1f - h),
            width = w,
            height = h,
        )
    }

    fun intersects(other: RoiRect): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom

    /** Fraction of [other]'s area that falls inside this rectangle. */
    fun overlapFractionOf(other: RoiRect): Float {
        val w = (minOf(right, other.right) - maxOf(left, other.left)).coerceAtLeast(0f)
        val h = (minOf(bottom, other.bottom) - maxOf(top, other.top)).coerceAtLeast(0f)
        val area = other.width * other.height
        return if (area <= 0f) 0f else (w * h) / area
    }

    companion object {
        val Default = RoiRect(0.32f, 0.24f, 0.36f, 0.5f)
    }
}

enum class DetectorMode(val label: String, val description: String) {
    MOTION(
        "Motion",
        "Frame differencing inside the box. Fastest and most reliable at distance; " +
            "background motion is subtracted so waves and camera shake do not trigger it.",
    ),
    OBJECT(
        "Object tracking (AI)",
        "On-device ML Kit object detector. Tracks moving subjects entering the box. " +
            "Good when the background moves a lot.",
    ),
    POSE(
        "Person / pose (AI)",
        "On-device ML Kit pose detector. Only fires for a human body. Needs the rider " +
            "to be reasonably large in frame.",
    ),
    MOTION_THEN_POSE(
        "Motion + person confirm",
        "Motion opens the gate, the pose detector confirms it was a person. " +
            "Fewest false positives, slightly slower to fire.",
    ),
}

enum class VideoCodecOption(val label: String, val mime: String) {
    H264("H.264 / AVC", "video/avc"),
    HEVC("H.265 / HEVC", "video/hevc"),
}

enum class AwbOption(val label: String, val mode: Int) {
    AUTO("Auto", 1),
    INCANDESCENT("Incandescent", 2),
    FLUORESCENT("Fluorescent", 3),
    DAYLIGHT("Daylight", 5),
    CLOUDY("Cloudy", 6),
    SHADE("Shade", 8),
}

data class AppSettings(
    // ---- Lens ----
    val cameraId: String? = null,
    val zoomRatio: Float = 1f,

    // ---- Video format ----
    val widthPx: Int = 1920,
    val heightPx: Int = 1080,
    val fps: Int = 60,
    val bitrateMbps: Int = 40,
    val codec: VideoCodecOption = VideoCodecOption.H264,
    val audioEnabled: Boolean = true,
    val stabilisation: Boolean = true,

    // ---- Clip timing ----
    val preRollSec: Float = 5f,
    val postRollSec: Float = 5f,
    val maxClipSec: Float = 30f,

    // ---- Detection ----
    val detectorMode: DetectorMode = DetectorMode.MOTION,
    val sensitivity: Float = 0.5f,
    val minConsecutiveHits: Int = 2,
    val cooldownSec: Float = 3f,
    val armDelaySec: Float = 5f,
    val roi: RoiRect = RoiRect.Default,
    val showDetectionOverlay: Boolean = true,

    // ---- Experimental manual controls ----
    val manualControlsEnabled: Boolean = false,
    val manualExposure: Boolean = false,
    val shutterNs: Long = 2_000_000L,
    val iso: Int = 200,
    val manualFocus: Boolean = false,
    val focusDiopters: Float = 0f,
    val evCompensationSteps: Int = 0,
    val awb: AwbOption = AwbOption.AUTO,

    // ---- Misc ----
    val dimScreenWhenArmed: Boolean = true,
) {
    val bitrateBps: Int get() = bitrateMbps * 1_000_000
    val preRollUs: Long get() = (preRollSec * 1_000_000f).toLong()
    val postRollUs: Long get() = (postRollSec * 1_000_000f).toLong()
    val maxClipUs: Long get() = (maxClipSec * 1_000_000f).toLong()

    /** Shutter speed rendered as a photographic fraction, e.g. "1/500s". */
    val shutterLabel: String
        get() {
            val seconds = shutterNs / 1_000_000_000.0
            if (seconds <= 0.0) return "—"
            return if (seconds >= 1.0) String.format(java.util.Locale.US, "%.1fs", seconds)
            else "1/${Math.round(1.0 / seconds)}s"
        }
}
