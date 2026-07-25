package com.kickercam.detect

import android.media.Image
import com.kickercam.settings.RoiRect
import kotlin.math.abs

/**
 * Luma frame differencing on a coarse grid.
 *
 * Activity is measured separately inside and outside the box, and a hit needs the inside to beat the
 * outside by a healthy margin. That subtraction is what keeps waves, moving water, swaying trees,
 * wind buffeting the tripod and auto-exposure steps from firing the trigger — they all move the
 * whole frame, not just the box.
 */
class MotionDetector {

    private val previous = ByteArray(GRID_W * GRID_H)
    private val current = ByteArray(GRID_W * GRID_H)
    private val diff = IntArray(GRID_W * GRID_H)
    private var hasPrevious = false
    private var warmupFrames = 0

    fun reset() {
        hasPrevious = false
        warmupFrames = 0
    }

    /**
     * @param imageRoi the box in analysis-image space
     * @param displayRotation rotation from image space to display space, for reporting boxes back
     */
    fun analyze(
        image: Image,
        imageRoi: RoiRect,
        displayRotation: Int,
        sensitivity: Float,
    ): DetectionOutcome {
        sampleLuma(image, current)

        if (!hasPrevious) {
            System.arraycopy(current, 0, previous, 0, current.size)
            hasPrevious = true
            warmupFrames = 0
            return DetectionOutcome()
        }

        var insideCount = 0
        var outsideCount = 0
        var insideSum = 0L
        var outsideSum = 0L
        var activeInside = 0

        val cellThreshold = lerp(26f, 7f, sensitivity).toInt()

        var minX = GRID_W
        var minY = GRID_H
        var maxX = -1
        var maxY = -1

        for (y in 0 until GRID_H) {
            val cellCenterY = (y + 0.5f) / GRID_H
            for (x in 0 until GRID_W) {
                val i = y * GRID_W + x
                val d = abs((current[i].toInt() and 0xFF) - (previous[i].toInt() and 0xFF))
                diff[i] = d

                val cellCenterX = (x + 0.5f) / GRID_W
                val inside = cellCenterX >= imageRoi.left && cellCenterX < imageRoi.right &&
                    cellCenterY >= imageRoi.top && cellCenterY < imageRoi.bottom

                if (inside) {
                    insideCount++
                    insideSum += d
                    if (d > cellThreshold) {
                        activeInside++
                        if (x < minX) minX = x
                        if (y < minY) minY = y
                        if (x > maxX) maxX = x
                        if (y > maxY) maxY = y
                    }
                } else {
                    outsideCount++
                    outsideSum += d
                }
            }
        }

        System.arraycopy(current, 0, previous, 0, current.size)

        val insideMean = if (insideCount > 0) insideSum.toFloat() / insideCount else 0f
        val outsideMean = if (outsideCount > 0) outsideSum.toFloat() / outsideCount else 0f
        val activeFraction = if (insideCount > 0) activeInside.toFloat() / insideCount else 0f

        // Let auto-exposure and white balance settle before arming.
        if (warmupFrames < WARMUP_FRAMES) {
            warmupFrames++
            return DetectionOutcome(
                roiEnergy = normalise(insideMean),
                backgroundEnergy = normalise(outsideMean),
                label = "warming up",
            )
        }

        val meanThreshold = lerp(9f, 2.2f, sensitivity)
        val requiredActiveFraction = lerp(0.10f, 0.012f, sensitivity)
        val backgroundMargin = lerp(2.6f, 1.35f, sensitivity)

        // A whole-frame jump is an exposure change or a knock, never a rider.
        val globalChange = outsideMean > GLOBAL_CHANGE_LEVEL

        val hit = !globalChange &&
            insideMean > meanThreshold &&
            activeFraction > requiredActiveFraction &&
            insideMean > outsideMean * backgroundMargin + 0.8f

        val boxes = if (maxX >= 0) {
            val imageBox = RoiRect(
                left = minX.toFloat() / GRID_W,
                top = minY.toFloat() / GRID_H,
                width = (maxX - minX + 1).toFloat() / GRID_W,
                height = (maxY - minY + 1).toFloat() / GRID_H,
            )
            listOf(RoiMapper.imageToDisplay(imageBox, displayRotation))
        } else {
            emptyList()
        }

        return DetectionOutcome(
            hit = hit,
            confidence = (insideMean / (meanThreshold * 2f)).coerceIn(0f, 1f),
            boxes = boxes,
            roiEnergy = normalise(insideMean),
            backgroundEnergy = normalise(outsideMean),
            label = if (globalChange) "frame-wide change ignored" else null,
        )
    }

    /** Nearest-neighbour subsample of the luma plane onto the fixed grid. */
    private fun sampleLuma(image: Image, out: ByteArray) {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val width = image.width
        val height = image.height

        for (gy in 0 until GRID_H) {
            val srcY = (gy * height / GRID_H).coerceIn(0, height - 1)
            val rowStart = srcY * rowStride
            for (gx in 0 until GRID_W) {
                val srcX = (gx * width / GRID_W).coerceIn(0, width - 1)
                val index = rowStart + srcX * pixelStride
                out[gy * GRID_W + gx] = if (index < buffer.limit()) buffer.get(index) else 0
            }
        }
    }

    private fun normalise(meanDiff: Float): Float = (meanDiff / 30f).coerceIn(0f, 1f)

    private companion object {
        const val GRID_W = 64
        const val GRID_H = 36
        const val WARMUP_FRAMES = 8
        const val GLOBAL_CHANGE_LEVEL = 14f
    }
}
