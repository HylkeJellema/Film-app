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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.kickercam.settings.RoiRect
import com.kickercam.ui.theme.KickerGreen
import com.kickercam.ui.theme.KickerOrange

/**
 * The detection box: drag the middle to move it onto the kicker, drag a corner or an edge to resize.
 *
 * Everything is drawn against [fit], the rectangle the camera image actually occupies, rather than
 * against the whole viewfinder — the box marks a region of the *picture*, so on a letterboxed preview
 * it has to stay on the picture and off the black bars.
 *
 * Coordinates are normalised so the box keeps its framing across resolution and lens changes.
 */
@Composable
fun RoiOverlay(
    roi: RoiRect,
    fit: PreviewFit?,
    detectionBoxes: List<RoiRect>,
    triggered: Boolean,
    editable: Boolean,
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

    var dragMode by remember { mutableStateOf(DragMode.NONE) }

    // The gesture detector is installed once and outlives every recomposition, so it must not close
    // over the box or the image rectangle it happened to see first — that froze the box at its
    // starting shape and made every drag snap back.
    val currentRoi by rememberUpdatedState(roi)
    val currentFit by rememberUpdatedState(fit)
    val changeRoi by rememberUpdatedState(onRoiChange)
    val commitRoi by rememberUpdatedState(onRoiCommit)

    val gestures = if (editable) {
        Modifier.pointerInput(Unit) {
            detectDragGestures(
                onDragStart = { start ->
                    val area = currentFit ?: return@detectDragGestures
                    if (area.width <= 0f || area.height <= 0f) return@detectDragGestures
                    dragMode = hitTest(
                        // Into the image's own coordinates: the box lives on the picture, not on the
                        // black bars around it.
                        point = Offset(start.x - area.left, start.y - area.top),
                        roi = currentRoi,
                        width = area.width,
                        height = area.height,
                        tolerancePx = handleTouchPx,
                    )
                },
                onDragEnd = {
                    if (dragMode != DragMode.NONE) commitRoi()
                    dragMode = DragMode.NONE
                },
                onDragCancel = { dragMode = DragMode.NONE },
                onDrag = { _, delta ->
                    val area = currentFit ?: return@detectDragGestures
                    if (area.width <= 0f || area.height <= 0f) return@detectDragGestures
                    if (dragMode == DragMode.NONE) return@detectDragGestures
                    changeRoi(
                        applyDrag(currentRoi, dragMode, delta.x / area.width, delta.y / area.height),
                    )
                },
            )
        }
    } else {
        Modifier
    }

    Canvas(modifier = modifier.fillMaxSize().then(gestures)) {
        val area = fit ?: return@Canvas

        val left = area.left + roi.left * area.width
        val top = area.top + roi.top * area.height
        val width = roi.width * area.width
        val height = roi.height * area.height

        // Darken the rest of the picture so the framing is unmistakable. Only the picture: the bars
        // outside it are already black.
        val shade = Color.Black.copy(alpha = 0.32f)
        drawRect(shade, Offset(area.left, area.top), GeometrySize(area.width, top - area.top))
        drawRect(shade, Offset(area.left, top + height), GeometrySize(area.width, area.bottom - top - height))
        drawRect(shade, Offset(area.left, top), GeometrySize(left - area.left, height))
        drawRect(shade, Offset(left + width, top), GeometrySize(area.right - left - width, height))

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

            // Edge grips, so one side can be nudged without hunting for a corner.
            val edges = listOf(
                Offset(left + width / 2f, top),
                Offset(left + width / 2f, top + height),
                Offset(left, top + height / 2f),
                Offset(left + width, top + height / 2f),
            )
            for (edge in edges) {
                drawCircle(color = boxColor, radius = handleDrawPx * 0.6f, center = edge)
            }
        }

        if (showDetections) {
            for (box in detectionBoxes) {
                drawRect(
                    color = KickerGreen.copy(alpha = 0.9f),
                    topLeft = Offset(area.left + box.left * area.width, area.top + box.top * area.height),
                    size = GeometrySize(box.width * area.width, box.height * area.height),
                    style = Stroke(width = strokePx * 0.6f),
                )
            }
        }
    }
}
