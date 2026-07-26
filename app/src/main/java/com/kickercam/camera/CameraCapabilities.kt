package com.kickercam.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.MediaCodec
import android.os.Build
import android.util.Log
import android.util.Range
import android.util.Size
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

private const val TAG = "CameraCapabilities"

/** Widest size three simultaneous streams manage on essentially any device, so the default. */
private const val DEFAULT_MAX_WIDTH = 1920

/** How closely two aspect ratios must agree to count as the same shape. */
private const val ASPECT_TOLERANCE = 0.02f

/**
 * A width and height, as plain numbers.
 *
 * The rules below work in these rather than [Size] purely so they can be tested: `android.util.Size`
 * is a stub in unit tests and throws on `getWidth`, and this rule is the one place a shape mismatch
 * could creep back in, so it is worth being able to test.
 */
internal typealias Dimensions = Pair<Int, Int>

private val Dimensions.width: Int get() = first
private val Dimensions.height: Int get() = second
private val Dimensions.aspect: Float get() = width.toFloat() / height.toFloat()

/**
 * The sizes worth offering out of everything a camera advertises: those in the shape the sensor
 * produces, largest first.
 *
 * The largest advertised size defines the shape and only sizes matching it survive. That is what keeps
 * the viewfinder honest: every option has the same aspect ratio, so changing resolution changes the
 * pixel count and nothing else. A list that mixed shapes is how the app came to ask for one the sensor
 * does not stream and then draw the result in a box that did not match it.
 */
internal fun sameShapeSizes(advertised: List<Dimensions>): List<Dimensions> {
    val largest = advertised.firstOrNull() ?: return listOf(1920 to 1080)
    return advertised.filter { abs(it.aspect - largest.aspect) < ASPECT_TOLERANCE }
}

/**
 * The size to stream at, given what was asked for.
 *
 * A request the camera does not advertise in its own shape is not honoured — it resolves to the
 * largest option up to 1080p, which is what three simultaneous streams reliably manage.
 */
internal fun chooseSize(advertised: List<Dimensions>, requested: Dimensions?): Dimensions {
    val options = sameShapeSizes(advertised)
    return options.firstOrNull { it == requested }
        ?: options.firstOrNull { it.width <= DEFAULT_MAX_WIDTH }
        ?: options.lastOrNull()
        ?: (1920 to 1080)
}

/** Diagonal of a 36x24mm full-frame sensor, used for 35mm-equivalent focal lengths. */
private const val FULL_FRAME_DIAGONAL_MM = 43.2666f

data class CameraDescriptor(
    val cameraId: String,
    val facing: Int,
    val sensorOrientation: Int,
    val hardwareLevel: Int,
    val focalLengthsMm: List<Float>,
    val equivalentFocalMm: Float?,
    val zoomRange: Range<Float>,
    val videoSizes: List<Size>,
    val maxFpsForSize: Map<Size, Int>,
    val availableFps: List<Int>,
    val aeFpsRanges: List<Range<Int>>,
    val isoRange: Range<Int>?,
    val exposureTimeRangeNs: Range<Long>?,
    val minFocusDiopters: Float,
    val supportsManualSensor: Boolean,
    val hasOis: Boolean,
    val hasEis: Boolean,
    val evRange: Range<Int>,
    val evStep: Double,
    val timestampIsRealtime: Boolean,
) {
    val isBackFacing: Boolean get() = facing == CameraCharacteristics.LENS_FACING_BACK
    val supportsZoomRatio: Boolean get() = zoomRange.upper > zoomRange.lower

    fun maxFps(size: Size): Int = maxFpsForSize[size] ?: 30

    /** Frame rates that can be paired with [size]: the advertised ones this size can keep up with. */
    fun fpsOptionsFor(size: Size): List<Int> {
        val cap = maxFps(size)
        return availableFps.filter { it <= cap }.ifEmpty { listOf(minOf(30, cap)) }
    }

    /**
     * The sizes worth offering: every size the camera advertises in the shape the sensor produces,
     * largest first.
     *
     * The shape is deliberately fixed — the largest advertised size defines it and only sizes matching
     * it are listed. That is what keeps the viewfinder honest: every option here has the same aspect
     * ratio, so changing resolution changes the pixel count and nothing else. The old resolution list
     * mixed shapes, which is how the app came to ask for one the sensor does not stream and then draw
     * the result in a box that did not match it.
     */
    val videoSizeOptions: List<Size>
        get() = sameShapeSizes(videoSizes.map { it.width to it.height }).map { Size(it.first, it.second) }

    /**
     * The size to actually use, given what was asked for.
     *
     * A request that is not one of [videoSizeOptions] is not honoured — it resolves to the largest
     * option up to 1080p, which is what three simultaneous streams reliably manage.
     */
    fun videoSizeFor(requested: Size?): Size {
        val chosen = chooseSize(
            advertised = videoSizes.map { it.width to it.height },
            requested = requested?.let { it.width to it.height },
        )
        return Size(chosen.first, chosen.second)
    }

    /**
     * The auto-exposure frame-rate range to request for a target rate.
     *
     * Asking for an arbitrary `Range(fps, fps)` the HAL never advertised is a good way to get a
     * rejected request or a silently wrong frame rate, so this only ever returns something the
     * device actually listed. A fixed range is preferred: a variable one lets auto-exposure drop the
     * frame rate to gather light, which is the last thing you want filming action.
     */
    fun bestFpsRange(targetFps: Int): Range<Int> {
        if (aeFpsRanges.isEmpty()) return Range(targetFps, targetFps)
        aeFpsRanges.firstOrNull { it.lower == targetFps && it.upper == targetFps }?.let { return it }
        aeFpsRanges.filter { it.upper == targetFps }
            .maxByOrNull { it.lower }
            ?.let { return it }
        aeFpsRanges.filter { it.contains(targetFps) }
            .minByOrNull { it.upper - it.lower }
            ?.let { return it }
        return aeFpsRanges.maxByOrNull { it.upper } ?: Range(targetFps, targetFps)
    }
}

/**
 * One selectable entry in the lens picker.
 *
 * Reaching a specific lens on Android is device dependent, so two routes are offered:
 *  - [isZoomPreset] `false` — a top-level camera id. Most direct route; Samsung exposes the tele
 *    modules this way.
 *  - [isZoomPreset] `true` — the main logical camera driven to a zoom ratio. The HAL picks the
 *    matching physical lens itself; this is the most reliable way to land on the 5x on Samsung.
 *
 * Streaming directly from a physical sub-camera was a third route. It worked on few devices, capped
 * the resolution, and reported a sensor mounting that did not match the frames it produced, so it is
 * gone.
 */
data class LensOption(
    val key: String,
    val cameraId: String,
    val zoomRatio: Float,
    val label: String,
    val detail: String,
    val facing: Int,
    val isZoomPreset: Boolean,
)

class CameraCapabilities(context: Context) {

    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    val cameras: List<CameraDescriptor> = buildList {
        val ids = runCatching { manager.cameraIdList }.getOrElse {
            Log.e(TAG, "Cannot list cameras", it)
            emptyArray()
        }
        for (id in ids) {
            runCatching { describe(id) }
                .onFailure { Log.w(TAG, "Skipping camera $id", it) }
                .getOrNull()
                ?.let { add(it) }
        }
    }

    fun descriptor(cameraId: String?): CameraDescriptor? =
        cameras.firstOrNull { it.cameraId == cameraId }

    /** The camera that acts as "1x": the first back-facing camera the platform reports. */
    val referenceBackCamera: CameraDescriptor? =
        cameras.firstOrNull { it.isBackFacing } ?: cameras.firstOrNull()

    val defaultCameraId: String? = referenceBackCamera?.cameraId

    private val referenceEquivalentMm: Float? =
        referenceBackCamera?.equivalentFocalMm?.takeIf { it > 0f }

    /**
     * Zoom factor of [descriptor] relative to the main camera, e.g. 5.0 for a 5x tele.
     *
     * Only meaningful between cameras facing the same way — a front camera's focal length compared
     * against the rear main camera's is a number with no physical meaning.
     */
    fun relativeZoom(descriptor: CameraDescriptor): Float? {
        if (!descriptor.isBackFacing) return null
        val ref = referenceEquivalentMm ?: return null
        val equiv = descriptor.equivalentFocalMm ?: return null
        return equiv / ref
    }

    fun lensOptions(): List<LensOption> = buildList {
        // 1. Directly addressable camera ids.
        for (cam in cameras) {
            val zoom = relativeZoom(cam)
            val equiv = cam.equivalentFocalMm
            val facingName = when (cam.facing) {
                CameraCharacteristics.LENS_FACING_FRONT -> "Front"
                CameraCharacteristics.LENS_FACING_EXTERNAL -> "External"
                else -> lensNameForZoom(zoom)
            }
            val zoomText = zoom?.let { formatZoom(it) }
            add(
                LensOption(
                    key = "cam:${cam.cameraId}",
                    cameraId = cam.cameraId,
                    zoomRatio = 1f,
                    label = listOfNotNull(facingName, zoomText).joinToString(" "),
                    detail = buildString {
                        append("Camera ${cam.cameraId}")
                        equiv?.let { append(" · ${it.roundToInt()}mm equiv") }
                        val maxSize = cam.videoSizes.firstOrNull()
                        maxSize?.let { append(" · up to ${it.width}x${it.height}@${cam.maxFps(it)}") }
                    },
                    facing = cam.facing,
                    isZoomPreset = false,
                ),
            )
        }

        // 2. Zoom-ratio presets on the main camera — lets the HAL switch to the real tele module.
        val main = referenceBackCamera
        if (main != null && main.supportsZoomRatio) {
            val range = main.zoomRange
            val presets = listOf(range.lower, 1f, 2f, 3f, 5f, 10f)
                .filter { it >= range.lower && it <= range.upper }
                .distinctBy { (it * 10).roundToInt() }
            for (ratio in presets) {
                // A 1.0x preset is identical to selecting the main camera id directly.
                if (abs(ratio - 1f) < 0.01f) continue
                add(
                    LensOption(
                        key = "zoom:${main.cameraId}:${formatZoom(ratio)}",
                        cameraId = main.cameraId,
                        zoomRatio = ratio,
                        label = "Main @ ${formatZoom(ratio)}",
                        detail = "Main camera driven to ${formatZoom(ratio)} — the phone picks the " +
                            "matching lens itself. Most reliable route to the tele module.",
                        facing = main.facing,
                        isZoomPreset = true,
                    ),
                )
            }
        }
    }

    private fun lensNameForZoom(zoom: Float?): String = when {
        zoom == null -> "Back"
        zoom < 0.85f -> "Ultra-wide"
        zoom < 1.6f -> "Main"
        else -> "Tele"
    }

    private fun describe(cameraId: String): CameraDescriptor {
        val c = manager.getCameraCharacteristics(cameraId)
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: throw IllegalStateException("no stream configuration map")

        val focalLengths = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.toList().orEmpty()
        val physicalSize = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val equivalent = if (focalLengths.isNotEmpty() && physicalSize != null) {
            val diagonal = hypot(physicalSize.width.toDouble(), physicalSize.height.toDouble()).toFloat()
            if (diagonal > 0f) focalLengths.min() * (FULL_FRAME_DIAGONAL_MM / diagonal) else null
        } else {
            null
        }

        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList().orEmpty()
        val manualSensor = caps.contains(
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR,
        )

        val videoSizes = collectVideoSizes(map)
        val maxFpsForSize = videoSizes.associateWith { size ->
            val minDuration = runCatching {
                map.getOutputMinFrameDuration(MediaCodec::class.java, size)
            }.getOrDefault(0L)
            if (minDuration > 0L) (1_000_000_000.0 / minDuration).toInt() else 30
        }

        val fpsRanges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.toList().orEmpty()
        val availableFps = fpsRanges
            .map { it.upper }
            .plus(fpsRanges.filter { it.lower == it.upper }.map { it.lower })
            .distinct()
            .filter { it >= 24 }
            .sorted()

        val zoomRange = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) ?: Range(1f, 1f)
        } else {
            Range(1f, 1f)
        }


        val oisModes = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
            ?.toList().orEmpty()
        val eisModes = c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
            ?.toList().orEmpty()

        val evStepRational = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
        val evStep = if (evStepRational != null && evStepRational.denominator != 0) {
            evStepRational.numerator.toDouble() / evStepRational.denominator.toDouble()
        } else {
            1.0 / 3.0
        }

        return CameraDescriptor(
            cameraId = cameraId,
            facing = c.get(CameraCharacteristics.LENS_FACING) ?: CameraCharacteristics.LENS_FACING_BACK,
            sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
            hardwareLevel = c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) ?: -1,
            focalLengthsMm = focalLengths,
            equivalentFocalMm = equivalent,
            zoomRange = zoomRange,
            videoSizes = videoSizes,
            maxFpsForSize = maxFpsForSize,
            availableFps = availableFps.ifEmpty { listOf(30) },
            aeFpsRanges = fpsRanges,
            isoRange = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE),
            exposureTimeRangeNs = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE),
            minFocusDiopters = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f,
            supportsManualSensor = manualSensor,
            hasOis = oisModes.any { it != CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF },
            hasEis = eisModes.any { it != CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF },
            evRange = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(0, 0),
            evStep = evStep,
            timestampIsRealtime = c.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
                CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME,
        )
    }

    private fun collectVideoSizes(map: StreamConfigurationMap): List<Size> {
        val raw = runCatching { map.getOutputSizes(MediaCodec::class.java) }.getOrNull()
            ?: runCatching { map.getOutputSizes(android.graphics.SurfaceTexture::class.java) }.getOrNull()
            ?: emptyArray()
        // No aspect filtering: filtering shapes out of the list is how the app came to record in a
        // shape the sensor does not produce. These are the sizes the camera says it has.
        return raw
            .filter { it.width >= 640 && it.height >= 480 }
            .distinctBy { it.width to it.height }
            .sortedWith(compareByDescending<Size> { it.width.toLong() * it.height }.thenByDescending { it.width })
    }

    companion object {
        /** Fixed locale: this string also identifies the zoom presets in the lens list. */
        fun formatZoom(zoom: Float): String = String.format(Locale.US, "%.1fx", zoom)
    }
}

fun formatZoom(zoom: Float): String = CameraCapabilities.formatZoom(zoom)
