package com.kickercam.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GeometrySize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.kickercam.settings.RoiRect
import com.kickercam.ui.theme.KickerGreen
import com.kickercam.ui.theme.KickerOrange
import kotlin.math.abs

private enum class DragMode { NONE, MOVE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

/**
 * The detection box: drag the middle to move it onto the kicker, drag a corner to resize.
 *
 * Coordinates are normalised so the box keeps its framing across resolution and lens changes.
 */
@Composable
fun RoiOverlay(
    roi: RoiRect,
    detectionBoxes: List<RoiRect>,
    triggered: Boolean,
    editable: Boolean,
    showDetections: Boolean,
    onRoiChange: (RoiRect) -> Unit,
    onRoiCommit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val handleTouchPx = remember(density) { with(density) { 30.dp.toPx() } }
    val handleDrawPx = remember(density) { with(density) { 11.dp.toPx() } }
    val strokePx = remember(density) { with(density) { 2.dp.toPx() } }

    var canvasSize by remember { mutableStateOf(GeometrySize.Zero) }
    var dragMode by remember { mutableStateOf(DragMode.NONE) }

    val gestures = if (editable) {
        Modifier.pointerInput(Unit) {
            detectDragGestures(
                onDragStart = { start ->
                    val size = canvasSize
                    if (size.width <= 0f || size.height <= 0f) return@detectDragGestures
                    dragMode = hitTest(start, roi, size, handleTouchPx)
                },
                onDragEnd = {
                    if (dragMode != DragMode.NONE) onRoiCommit()
                    dragMode = DragMode.NONE
                },
                onDragCancel = { dragMode = DragMode.NONE },
                onDrag = { _, delta ->
                    val size = canvasSize
                    if (size.width <= 0f || size.height <= 0f) return@detectDragGestures
                    val dx = delta.x / size.width
                    val dy = delta.y / size.height
                    onRoiChange(applyDrag(roi, dragMode, dx, dy))
                },
            )
        }
    } else {
        Modifier
    }

    Canvas(modifier = modifier.fillMaxSize().then(gestures)) {
        canvasSize = size

        val left = roi.left * size.width
        val top = roi.top * size.height
        val width = roi.width * size.width
        val height = roi.height * size.height

        // Darken everything outside the box so the framing is unmistakable.
        val shade = Color.Black.copy(alpha = 0.32f)
        drawRect(shade, Offset(0f, 0f), GeometrySize(size.width, top))
        drawRect(shade, Offset(0f, top + height), GeometrySize(size.width, size.height - top - height))
        drawRect(shade, Offset(0f, top), GeometrySize(left, height))
        drawRect(
            shade,
            Offset(left + width, top),
            GeometrySize(size.width - left - width, height),
        )

        val boxColor = if (triggered) KickerGreen else KickerOrange
        drawRect(
            color = boxColor,
            topLeft = Offset(left, top),
            size = GeometrySize(width, height),
            style = Stroke(width = strokePx),
        )

        if (editable) {
            val corners = listOf(
                Offset(left, top),
                Offset(left + width, top),
                Offset(left, top + height),
                Offset(left + width, top + height),
            )
            for (corner in corners) {
                drawCircle(color = boxColor, radius = handleDrawPx, center = corner)
                drawCircle(color = Color.Black.copy(alpha = 0.55f), radius = handleDrawPx * 0.45f, center = corner)
            }
        }

        if (showDetections) {
            for (box in detectionBoxes) {
                drawRect(
                    color = KickerGreen.copy(alpha = 0.9f),
                    topLeft = Offset(box.left * size.width, box.top * size.height),
                    size = GeometrySize(box.width * size.width, box.height * size.height),
                    style = Stroke(width = strokePx * 0.6f),
                )
            }
        }
    }
}

private fun hitTest(point: Offset, roi: RoiRect, size: GeometrySize, tolerancePx: Float): DragMode {
    val left = roi.left * size.width
    val top = roi.top * size.height
    val right = roi.right * size.width
    val bottom = roi.bottom * size.height

    val nearLeft = abs(point.x - left) <= tolerancePx
    val nearRight = abs(point.x - right) <= tolerancePx
    val nearTop = abs(point.y - top) <= tolerancePx
    val nearBottom = abs(point.y - bottom) <= tolerancePx

    return when {
        nearLeft && nearTop -> DragMode.TOP_LEFT
        nearRight && nearTop -> DragMode.TOP_RIGHT
        nearLeft && nearBottom -> DragMode.BOTTOM_LEFT
        nearRight && nearBottom -> DragMode.BOTTOM_RIGHT
        point.x in left..right && point.y in top..bottom -> DragMode.MOVE
        else -> DragMode.NONE
    }
}

private fun applyDrag(roi: RoiRect, mode: DragMode, dx: Float, dy: Float): RoiRect {
    val minSize = 0.06f
    return when (mode) {
        DragMode.NONE -> roi

        DragMode.MOVE -> RoiRect(
            left = (roi.left + dx).coerceIn(0f, 1f - roi.width),
            top = (roi.top + dy).coerceIn(0f, 1f - roi.height),
            width = roi.width,
            height = roi.height,
        )

        DragMode.TOP_LEFT -> {
            val newLeft = (roi.left + dx).coerceIn(0f, roi.right - minSize)
            val newTop = (roi.top + dy).coerceIn(0f, roi.bottom - minSize)
            RoiRect(newLeft, newTop, roi.right - newLeft, roi.bottom - newTop)
        }

        DragMode.TOP_RIGHT -> {
            val newRight = (roi.right + dx).coerceIn(roi.left + minSize, 1f)
            val newTop = (roi.top + dy).coerceIn(0f, roi.bottom - minSize)
            RoiRect(roi.left, newTop, newRight - roi.left, roi.bottom - newTop)
        }

        DragMode.BOTTOM_LEFT -> {
            val newLeft = (roi.left + dx).coerceIn(0f, roi.right - minSize)
            val newBottom = (roi.bottom + dy).coerceIn(roi.top + minSize, 1f)
            RoiRect(newLeft, roi.top, roi.right - newLeft, newBottom - roi.top)
        }

        DragMode.BOTTOM_RIGHT -> {
            val newRight = (roi.right + dx).coerceIn(roi.left + minSize, 1f)
            val newBottom = (roi.bottom + dy).coerceIn(roi.top + minSize, 1f)
            RoiRect(roi.left, roi.top, newRight - roi.left, newBottom - roi.top)
        }
    }.clampToUnit(minSize)
}
