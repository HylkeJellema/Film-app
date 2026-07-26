package com.kickercam.detect

import android.graphics.ImageFormat
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import com.kickercam.settings.DetectorMode
import com.kickercam.settings.RoiRect
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "DetectionPipeline"

/**
 * Owns the low-resolution analysis stream and runs the selected detector on it.
 *
 * Everything happens on one dedicated thread, and frames are dropped rather than queued when the
 * detector is still busy — a detector that falls behind should lose frames, not add latency.
 *
 * Each verdict carries the frame's own sensor timestamp, so a clip is centred on the instant the
 * rider was actually there rather than on the instant the detector finished thinking about it.
 */
class DetectionPipeline(
    private val analysisSize: Size,
    private val onVerdict: (outcome: DetectionOutcome, frameTimestampNs: Long) -> Unit,
) {

    private val thread = HandlerThread("kc-detect").apply { start() }
    private val handler = Handler(thread.looper)
    private val busy = AtomicBoolean(false)

    private val motionDetector = MotionDetector()
    private var objectDetector: ObjectTrackingDetector? = null
    private var poseDetector: PersonPoseDetector? = null

    @Volatile private var mode: DetectorMode = DetectorMode.MOTION
    @Volatile private var sensitivity: Float = 0.5f
    @Volatile private var roi: RoiRect = RoiRect.Default
    @Volatile private var enabled: Boolean = true
    @Volatile private var lastAnalysisAtMs: Long = 0L

    private val reader: ImageReader = ImageReader.newInstance(
        analysisSize.width,
        analysisSize.height,
        ImageFormat.YUV_420_888,
        3,
    ).apply {
        setOnImageAvailableListener({ r -> onImageAvailable(r) }, handler)
    }

    val surface: Surface get() = reader.surface

    fun configure(
        mode: DetectorMode,
        sensitivity: Float,
        roi: RoiRect,
    ) {
        val modeChanged = this.mode != mode
        this.mode = mode
        this.sensitivity = sensitivity
        this.roi = roi

        // Only pay for the ML models that the chosen mode actually needs.
        val needsObject = mode == DetectorMode.OBJECT
        val needsPose = mode == DetectorMode.POSE || mode == DetectorMode.MOTION_THEN_POSE

        if (needsObject && objectDetector == null) objectDetector = ObjectTrackingDetector()
        if (!needsObject) {
            objectDetector?.close()
            objectDetector = null
        }
        if (needsPose && poseDetector == null) poseDetector = PersonPoseDetector()
        if (!needsPose) {
            poseDetector?.close()
            poseDetector = null
        }

        if (modeChanged) {
            motionDetector.reset()
            objectDetector?.reset()
        }
    }

    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
        if (!enabled) motionDetector.reset()
    }

    fun release() {
        enabled = false
        // Everything is torn down on the analysis thread: closing the reader from another thread
        // while a frame is still checked out throws.
        handler.post {
            objectDetector?.close()
            objectDetector = null
            poseDetector?.close()
            poseDetector = null
            runCatching { reader.close() }
        }
        thread.quitSafely()
    }

    private fun onImageAvailable(reader: ImageReader) {
        val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return
        if (!enabled || busy.get() || throttled()) {
            image.close()
            return
        }
        busy.set(true)
        try {
            val timestampNs = image.timestamp
            val outcome = analyze(image)
            onVerdict(outcome, timestampNs)
        } catch (t: Throwable) {
            Log.w(TAG, "analysis failed", t)
        } finally {
            image.close()
            busy.set(false)
        }
    }

    /** Caps how often the heavier ML models run; motion differencing can keep up with the sensor. */
    private fun throttled(): Boolean {
        val minIntervalMs = when (mode) {
            DetectorMode.MOTION -> 25L
            DetectorMode.MOTION_THEN_POSE -> 30L
            else -> 50L
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastAnalysisAtMs < minIntervalMs) return true
        lastAnalysisAtMs = now
        return false
    }

    private fun analyze(image: Image): DetectionOutcome {
        // The frames arrive the same way round as they are shown, so the box needs no mapping: a
        // fraction of the viewfinder is the same fraction of the analysis frame.
        val box = roi

        return when (mode) {
            DetectorMode.MOTION ->
                motionDetector.analyze(image, box, sensitivity)

            DetectorMode.OBJECT ->
                objectDetector?.analyze(image, box, sensitivity) ?: DetectionOutcome()

            DetectorMode.POSE ->
                poseDetector?.analyze(image, box, sensitivity) ?: DetectionOutcome()

            DetectorMode.MOTION_THEN_POSE -> {
                val motion = motionDetector.analyze(image, box, sensitivity)
                if (!motion.hit) {
                    motion.copy(label = motion.label ?: "waiting for motion")
                } else {
                    val pose = poseDetector?.analyze(image, box, sensitivity)
                    if (pose == null) {
                        motion
                    } else {
                        motion.copy(
                            hit = pose.hit,
                            confidence = pose.confidence,
                            boxes = motion.boxes + pose.boxes,
                            label = if (pose.hit) "motion + person" else "motion, no person",
                        )
                    }
                }
            }
        }
    }
}
