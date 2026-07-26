package com.kickercam.ui

import androidx.compose.ui.geometry.Offset
import com.kickercam.settings.RoiRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The detection box is the one control that has to work under a finger on a phone screen, so its
 * geometry is pinned down here: dragging must actually change the box, resizing must not invert it,
 * and repeated deltas must accumulate instead of snapping back to where the drag started.
 */
class RoiDragTest {

    private val box = RoiRect(left = 0.3f, top = 0.3f, width = 0.4f, height = 0.4f)

    private fun assertBox(expected: RoiRect, actual: RoiRect, tolerance: Float = 1e-4f) {
        assertEquals("left", expected.left, actual.left, tolerance)
        assertEquals("top", expected.top, actual.top, tolerance)
        assertEquals("width", expected.width, actual.width, tolerance)
        assertEquals("height", expected.height, actual.height, tolerance)
    }

    // ------------------------------------------------------------------ resizing

    @Test
    fun `dragging the bottom-right corner grows the box without moving its origin`() {
        val result = applyDrag(box, DragMode.BOTTOM_RIGHT, dx = 0.1f, dy = 0.05f)
        assertBox(RoiRect(0.3f, 0.3f, 0.5f, 0.45f), result)
    }

    @Test
    fun `dragging the top-left corner moves the origin and shrinks the box`() {
        val result = applyDrag(box, DragMode.TOP_LEFT, dx = 0.1f, dy = 0.1f)
        assertBox(RoiRect(0.4f, 0.4f, 0.3f, 0.3f), result)
    }

    @Test
    fun `each edge resizes only its own side`() {
        assertBox(RoiRect(0.35f, 0.3f, 0.35f, 0.4f), applyDrag(box, DragMode.LEFT, 0.05f, 0f))
        assertBox(RoiRect(0.3f, 0.3f, 0.45f, 0.4f), applyDrag(box, DragMode.RIGHT, 0.05f, 0f))
        assertBox(RoiRect(0.3f, 0.35f, 0.4f, 0.35f), applyDrag(box, DragMode.TOP, 0f, 0.05f))
        assertBox(RoiRect(0.3f, 0.3f, 0.4f, 0.45f), applyDrag(box, DragMode.BOTTOM, 0f, 0.05f))
    }

    @Test
    fun `a corner dragged past the opposite corner cannot invert the box`() {
        // Fling the bottom-right handle way past the top-left one.
        val result = applyDrag(box, DragMode.BOTTOM_RIGHT, dx = -5f, dy = -5f)
        assertTrue("width must stay positive", result.width > 0f)
        assertTrue("height must stay positive", result.height > 0f)
        assertEquals(MIN_ROI_SIZE, result.width, 1e-4f)
        assertEquals(MIN_ROI_SIZE, result.height, 1e-4f)
        assertEquals(0.3f, result.left, 1e-4f)
    }

    @Test
    fun `resizing never escapes the frame`() {
        for (mode in DragMode.entries) {
            val result = applyDrag(box, mode, dx = 9f, dy = 9f)
            assertTrue("$mode left", result.left >= -1e-4f)
            assertTrue("$mode top", result.top >= -1e-4f)
            assertTrue("$mode right", result.right <= 1f + 1e-4f)
            assertTrue("$mode bottom", result.bottom <= 1f + 1e-4f)
        }
    }

    // ------------------------------------------------------------------ moving

    @Test
    fun `moving keeps the box the same size`() {
        val result = applyDrag(box, DragMode.MOVE, dx = 0.1f, dy = -0.1f)
        assertBox(RoiRect(0.4f, 0.2f, 0.4f, 0.4f), result)
    }

    @Test
    fun `moving into a corner stops at the edge instead of clipping the size`() {
        val result = applyDrag(box, DragMode.MOVE, dx = 5f, dy = 5f)
        assertEquals(0.4f, result.width, 1e-4f)
        assertEquals(0.4f, result.height, 1e-4f)
        assertEquals(0.6f, result.left, 1e-4f)
        assertEquals(0.6f, result.top, 1e-4f)
    }

    @Test
    fun `a NONE drag is inert`() {
        assertBox(box, applyDrag(box, DragMode.NONE, 0.2f, 0.2f))
    }

    // ------------------------------------------------------------------ accumulation

    @Test
    fun `successive deltas accumulate rather than snapping back`() {
        // This is the regression that made the box unusable: when the gesture handler read a stale
        // box, every frame recomputed from the original and the result never moved.
        var current = box
        repeat(10) { current = applyDrag(current, DragMode.MOVE, dx = 0.01f, dy = 0f) }
        assertEquals(0.4f, current.left, 1e-3f)

        var resized = box
        repeat(10) { resized = applyDrag(resized, DragMode.BOTTOM_RIGHT, dx = 0.01f, dy = 0f) }
        assertEquals(0.5f, resized.width, 1e-3f)
    }

    // ------------------------------------------------------------------ hit testing

    @Test
    fun `corners take precedence over edges and the body`() {
        val w = 1000f
        val h = 1000f
        val tolerance = 40f

        assertEquals(DragMode.TOP_LEFT, hitTest(Offset(300f, 300f), box, w, h, tolerance))
        assertEquals(DragMode.BOTTOM_RIGHT, hitTest(Offset(700f, 700f), box, w, h, tolerance))
        assertEquals(DragMode.TOP_RIGHT, hitTest(Offset(700f, 300f), box, w, h, tolerance))
        assertEquals(DragMode.BOTTOM_LEFT, hitTest(Offset(300f, 700f), box, w, h, tolerance))
    }

    @Test
    fun `edges are grabbable away from the corners`() {
        val w = 1000f
        val h = 1000f
        val tolerance = 40f

        assertEquals(DragMode.LEFT, hitTest(Offset(300f, 500f), box, w, h, tolerance))
        assertEquals(DragMode.RIGHT, hitTest(Offset(700f, 500f), box, w, h, tolerance))
        assertEquals(DragMode.TOP, hitTest(Offset(500f, 300f), box, w, h, tolerance))
        assertEquals(DragMode.BOTTOM, hitTest(Offset(500f, 700f), box, w, h, tolerance))
    }

    @Test
    fun `the middle moves and the outside is ignored`() {
        val w = 1000f
        val h = 1000f
        val tolerance = 40f

        assertEquals(DragMode.MOVE, hitTest(Offset(500f, 500f), box, w, h, tolerance))
        assertEquals(DragMode.NONE, hitTest(Offset(50f, 50f), box, w, h, tolerance))
        assertEquals(DragMode.NONE, hitTest(Offset(950f, 500f), box, w, h, tolerance))
    }
}
