package com.kickercam.ui

import androidx.compose.ui.geometry.Offset
import com.kickercam.settings.RoiRect
import kotlin.math.abs

/**
 * Hit-testing and resizing for the detection box, kept apart from the drawing so it can be tested
 * without a Compose runtime. Every value is a fraction of the camera image, never a pixel.
 */
internal enum class DragMode {
    NONE, MOVE,
    TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT,
    LEFT, RIGHT, TOP, BOTTOM,
}

internal fun hitTest(
    point: Offset,
    roi: RoiRect,
    width: Float,
    height: Float,
    tolerancePx: Float,
): DragMode {
    val left = roi.left * width
    val top = roi.top * height
    val right = roi.right * width
    val bottom = roi.bottom * height

    val nearLeft = abs(point.x - left) <= tolerancePx
    val nearRight = abs(point.x - right) <= tolerancePx
    val nearTop = abs(point.y - top) <= tolerancePx
    val nearBottom = abs(point.y - bottom) <= tolerancePx
    val withinX = point.x in (left - tolerancePx)..(right + tolerancePx)
    val withinY = point.y in (top - tolerancePx)..(bottom + tolerancePx)

    // Corners win over edges, edges over a plain move.
    return when {
        nearLeft && nearTop -> DragMode.TOP_LEFT
        nearRight && nearTop -> DragMode.TOP_RIGHT
        nearLeft && nearBottom -> DragMode.BOTTOM_LEFT
        nearRight && nearBottom -> DragMode.BOTTOM_RIGHT
        nearLeft && withinY -> DragMode.LEFT
        nearRight && withinY -> DragMode.RIGHT
        nearTop && withinX -> DragMode.TOP
        nearBottom && withinX -> DragMode.BOTTOM
        point.x in left..right && point.y in top..bottom -> DragMode.MOVE
        else -> DragMode.NONE
    }
}

internal const val MIN_ROI_SIZE = 0.06f

internal fun applyDrag(roi: RoiRect, mode: DragMode, dx: Float, dy: Float): RoiRect {
    // Each edge is resolved independently against the opposite edge, so a corner drag can never
    // invert the rectangle no matter how fast the finger moves.
    var left = roi.left
    var top = roi.top
    var right = roi.right
    var bottom = roi.bottom

    when (mode) {
        DragMode.NONE -> return roi

        DragMode.MOVE -> return RoiRect(
            left = (roi.left + dx).coerceIn(0f, 1f - roi.width),
            top = (roi.top + dy).coerceIn(0f, 1f - roi.height),
            width = roi.width,
            height = roi.height,
        )

        DragMode.TOP_LEFT -> {
            left = (left + dx).coerceIn(0f, right - MIN_ROI_SIZE)
            top = (top + dy).coerceIn(0f, bottom - MIN_ROI_SIZE)
        }

        DragMode.TOP_RIGHT -> {
            right = (right + dx).coerceIn(left + MIN_ROI_SIZE, 1f)
            top = (top + dy).coerceIn(0f, bottom - MIN_ROI_SIZE)
        }

        DragMode.BOTTOM_LEFT -> {
            left = (left + dx).coerceIn(0f, right - MIN_ROI_SIZE)
            bottom = (bottom + dy).coerceIn(top + MIN_ROI_SIZE, 1f)
        }

        DragMode.BOTTOM_RIGHT -> {
            right = (right + dx).coerceIn(left + MIN_ROI_SIZE, 1f)
            bottom = (bottom + dy).coerceIn(top + MIN_ROI_SIZE, 1f)
        }

        DragMode.LEFT -> left = (left + dx).coerceIn(0f, right - MIN_ROI_SIZE)
        DragMode.RIGHT -> right = (right + dx).coerceIn(left + MIN_ROI_SIZE, 1f)
        DragMode.TOP -> top = (top + dy).coerceIn(0f, bottom - MIN_ROI_SIZE)
        DragMode.BOTTOM -> bottom = (bottom + dy).coerceIn(top + MIN_ROI_SIZE, 1f)
    }

    return RoiRect(left, top, right - left, bottom - top).clampToUnit(MIN_ROI_SIZE)
}
