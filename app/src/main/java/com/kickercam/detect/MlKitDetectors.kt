package com.kickercam.detect

import android.media.Image
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.DetectedObject
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetector
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetector
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import com.kickercam.settings.RoiRect
import java.util.concurrent.TimeUnit
import kotlin.math.abs

private const val TAG = "MlKitDetectors"
private const val ML_TIMEOUT_MS = 800L

/**
 * ML Kit's on-device object detector in streaming mode.
 *
 * The detector is deliberately not asked to classify — the kicker, the boat and the rider are all
 * just "objects" to it. What makes a rider stand out is that the box *moves*, so a hit needs both
 * overlap with the region of interest and either a fresh tracking id or real displacement.
 */
class ObjectTrackingDetector : AutoCloseable {

    private val detector: ObjectDetector = ObjectDetection.getClient(
        ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .enableMultipleObjects()
            .enableClassification()
            .build(),
    )

    private val lastCenters = HashMap<Int, Pair<Float, Float>>()
    private val seenInsideRoi = HashSet<Int>()

    fun reset() {
        lastCenters.clear()
        seenInsideRoi.clear()
    }

    fun analyze(
        image: Image,
        displayRoi: RoiRect,
        rotationDegrees: Int,
        sensitivity: Float,
    ): DetectionOutcome {
        val objects = runDetector(image, rotationDegrees) ?: return DetectionOutcome()

        val (frameWidth, frameHeight) = rotatedDimensions(image, rotationDegrees)
        if (frameWidth <= 0 || frameHeight <= 0) return DetectionOutcome()

        val minOverlap = lerp(0.35f, 0.08f, sensitivity)
        val minMovement = lerp(0.035f, 0.006f, sensitivity)

        val boxes = ArrayList<RoiRect>(objects.size)
        var hit = false
        var bestConfidence = 0f
        var label: String? = null

        for (obj in objects) {
            val rect = obj.boundingBox
            val box = RoiRect(
                left = rect.left.toFloat() / frameWidth,
                top = rect.top.toFloat() / frameHeight,
                width = rect.width().toFloat() / frameWidth,
                height = rect.height().toFloat() / frameHeight,
            )
            boxes += box

            val overlap = displayRoi.overlapFractionOf(box)
            if (overlap < minOverlap) continue

            val id = obj.trackingId ?: -1
            val center = box.centerX to box.centerY
            val previous = lastCenters[id]
            val moved = previous == null ||
                abs(center.first - previous.first) > minMovement ||
                abs(center.second - previous.second) > minMovement
            val isNewInRoi = id !in seenInsideRoi

            if (moved || isNewInRoi) {
                hit = true
                bestConfidence = maxOf(bestConfidence, overlap)
                label = obj.labels.firstOrNull()?.text ?: "moving object"
            }
            if (id >= 0) seenInsideRoi += id
        }

        // Remember positions for the next frame and forget vanished tracks.
        val liveIds = objects.mapNotNull { it.trackingId }.toSet()
        lastCenters.keys.retainAll(liveIds)
        seenInsideRoi.retainAll(liveIds)
        for (obj in objects) {
            val id = obj.trackingId ?: continue
            val rect = obj.boundingBox
            lastCenters[id] = (rect.exactCenterX() / frameWidth) to (rect.exactCenterY() / frameHeight)
        }

        return DetectionOutcome(
            hit = hit,
            confidence = bestConfidence,
            boxes = boxes,
            label = label,
        )
    }

    private fun runDetector(image: Image, rotationDegrees: Int): List<DetectedObject>? = try {
        val input = InputImage.fromMediaImage(image, rotationDegrees)
        Tasks.await(detector.process(input), ML_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    } catch (t: Throwable) {
        Log.w(TAG, "object detection failed", t)
        null
    }

    override fun close() {
        runCatching { detector.close() }
    }
}

/**
 * ML Kit's on-device pose detector, used purely as a "is that a human?" test. Only fires when a body
 * is found overlapping the region of interest, which is the strictest of the available triggers.
 */
class PersonPoseDetector : AutoCloseable {

    private val detector: PoseDetector = PoseDetection.getClient(
        PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
            .build(),
    )

    fun analyze(
        image: Image,
        displayRoi: RoiRect,
        rotationDegrees: Int,
        sensitivity: Float,
    ): DetectionOutcome {
        val pose = runDetector(image, rotationDegrees) ?: return DetectionOutcome()

        val (frameWidth, frameHeight) = rotatedDimensions(image, rotationDegrees)
        if (frameWidth <= 0 || frameHeight <= 0) return DetectionOutcome()

        val minLikelihood = lerp(0.7f, 0.25f, sensitivity)
        val minLandmarks = lerp(9f, 4f, sensitivity).toInt()
        val minOverlap = lerp(0.4f, 0.1f, sensitivity)

        val landmarks = pose.allPoseLandmarks.filter { it.inFrameLikelihood >= minLikelihood }
        if (landmarks.size < minLandmarks) {
            return DetectionOutcome(label = "no person")
        }

        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var likelihoodSum = 0f
        for (landmark in landmarks) {
            minX = minOf(minX, landmark.position.x)
            minY = minOf(minY, landmark.position.y)
            maxX = maxOf(maxX, landmark.position.x)
            maxY = maxOf(maxY, landmark.position.y)
            likelihoodSum += landmark.inFrameLikelihood
        }

        val box = RoiRect(
            left = (minX / frameWidth).coerceIn(0f, 1f),
            top = (minY / frameHeight).coerceIn(0f, 1f),
            width = ((maxX - minX) / frameWidth).coerceIn(0f, 1f),
            height = ((maxY - minY) / frameHeight).coerceIn(0f, 1f),
        )

        val overlap = displayRoi.overlapFractionOf(box)
        val centerInside = box.centerX in displayRoi.left..displayRoi.right &&
            box.centerY in displayRoi.top..displayRoi.bottom

        return DetectionOutcome(
            hit = overlap >= minOverlap || centerInside,
            confidence = likelihoodSum / landmarks.size,
            boxes = listOf(box),
            label = "person (${landmarks.size} landmarks)",
        )
    }

    private fun runDetector(image: Image, rotationDegrees: Int): Pose? = try {
        val input = InputImage.fromMediaImage(image, rotationDegrees)
        Tasks.await(detector.process(input), ML_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    } catch (t: Throwable) {
        Log.w(TAG, "pose detection failed", t)
        null
    }

    override fun close() {
        runCatching { detector.close() }
    }
}

/** ML Kit reports coordinates in the upright frame, so 90/270 swap width and height. */
private fun rotatedDimensions(image: Image, rotationDegrees: Int): Pair<Int, Int> {
    val normalised = ((rotationDegrees % 360) + 360) % 360
    return if (normalised == 90 || normalised == 270) {
        image.height to image.width
    } else {
        image.width to image.height
    }
}
