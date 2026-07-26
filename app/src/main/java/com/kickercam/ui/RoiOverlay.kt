package com.kickercam.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GeometrySize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.kickercam.settings.RoiRect
import com.kickercam.ui.theme.KickerGreen
import com.kickercam.ui.theme.KickerOrange
import kotlin.math.abs

internal enum class DragMode {
    NONE, MOVE,
    TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT,
    LEFT, RIGHT, TOP, BOTTOM,
}

/**
 * The detection box: drag the middle to move it onto the kicker, drag a corner or an edge to resize.
 *
 * Coordinates are normalised so the box keeps its framing across resolution and lens changes.
 */
@Composable
fun RoiOverlay(
    roi: RoiRect,
    detectionBoxes: List<RoiRect>,
    triggered: Boolean,
    editable: Boolean,
    /** Draws the box heavier while it is being adjusted, so the mode is obvious. */
    emphasised: Boolean,
    showDetections: Boolean,
    onRoiChange: (RoiRect) -> Unit,
    onRoiCommit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val handleTouchPx = remember(density) { with(density) { 34.dp.toPx() } }
    val handleDrawPx = remember(density, emphasised) {
        with(density) { if (emphasised) 15.dp.toPx() else 12.dp.toPx() }
    }
    val strokePx = remember(density, emphasised) {
        with(density) { if (emphasised) 3.dp.toPx() else 2.dp.toPx() }
    }

    // The gesture lambdas below live for the lifetime of the pointerInput node, which must not be
    // re-keyed on every drag frame. They therefore have to read the *current* box and callbacks
    // indirectly — capturing them directly would freeze the values from first composition and make
    // every delta apply to the original rectangle, so the box would snap back on every move.
    val currentRoi by rememberUpdatedState(roi)
    val currentOnChange by rememberUpdatedState(onRoiChange)
    val currentOnCommit by rememberUpdatedState(onRoiCommit)

    var layoutSize by remember { mutableStateOf(IntSize.Zero) }
    var dragMode by remember { mutableStateOf(DragMode.NONE) }

    val gestures = if (editable) {
        Modifier.pointerInput(Unit) {
            detectDragGestures(
                onDragStart = { start ->
                    val size = layoutSize
                    if (size.width <= 0 || size.height <= 0) return@detectDragGestures
                    dragMode = hitTest(
                        point = start,
                        roi = currentRoi,
                        width = size.width.toFloat(),
                        height = size.height.toFloat(),
                        tolerancePx = handleTouchPx,
                    )
                },
                onDragEnd = {
                    if (dragMode != DragMode.NONE) currentOnCommit()
                    dragMode = DragMode.NONE
                },
                onDragCancel = { dragMode = DragMode.NONE },
                onDrag = { _, delta ->
                    val size = layoutSize
                    if (size.width <= 0 || size.height <= 0) return@detectDragGestures
                    if (dragMode == DragMode.NONE) return@detectDragGestures
                    currentOnChange(
                        applyDrag(
                            roi = currentRoi,
                            mode = dragMode,
                            dx = delta.x / size.width,
                            dy = delta.y / size.height,
                        ),
                    )
                },
            )
        }
    } else {
        Modifier
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { layoutSize = it }
            .then(gestures),
    ) {
        val left = roi.left * size.width
        val top = roi.top * size.height
        val width = roi.width * size.width
        val height = roi.height * size.height

        // Darken everything outside the box so the framing is unmistakable.
        val shade = Color.Black.copy(alpha = 0.32f)
        drawRect(shade, Offset(0f, 0f), GeometrySize(size.width, top))
        drawRect(shade, Offset(0f, top + height), GeometrySize(size.width, size.height - top - height))
        drawRect(shade, Offset(0f, top), GeometrySize(left, height))
        drawRect(shade, Offset(left + width, top), GeometrySize(size.width - left - width, height))

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
                drawCircle(Color.Black.copy(alpha = 0.55f), handleDrawPx * 0.45f, corner)
            }

            // Edge grips, so a box pushed against a screen edge is still resizable.
            val edges = listOf(
                Offset(left + width / 2f, top),
                Offset(left + width / 2f, top + height),
                Offset(left, top + height / 2f),
                Offset(left + width, top + height / 2f),
            )
            for (edge in edges) {
                drawCircle(color = boxColor.copy(alpha = 0.75f), radius = handleDrawPx * 0.6f, center = edge)
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
