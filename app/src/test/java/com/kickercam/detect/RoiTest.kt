package com.kickercam.detect

import com.kickercam.settings.RoiRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoiRectTest {

    @Test
    fun `clamps a box dragged past the edge back inside the frame`() {
        val clamped = RoiRect(left = 0.9f, top = -0.2f, width = 0.4f, height = 0.5f).clampToUnit()
        assertEquals(0.6f, clamped.left, 1e-5f)
        assertEquals(0f, clamped.top, 1e-5f)
        assertEquals(0.4f, clamped.width, 1e-5f)
        assertTrue(clamped.right <= 1f)
        assertTrue(clamped.bottom <= 1f)
    }

    @Test
    fun `enforces a minimum size so the box stays grabbable`() {
        val clamped = RoiRect(0.5f, 0.5f, 0.001f, 0.001f).clampToUnit(minSize = 0.06f)
        assertEquals(0.06f, clamped.width, 1e-5f)
        assertEquals(0.06f, clamped.height, 1e-5f)
    }

    @Test
    fun `overlap fraction measures how much of the subject is inside the box`() {
        val roi = RoiRect(0.0f, 0.0f, 0.5f, 0.5f)

        // Fully inside.
        assertEquals(1f, roi.overlapFractionOf(RoiRect(0.1f, 0.1f, 0.2f, 0.2f)), 1e-4f)
        // Exactly half inside horizontally.
        assertEquals(0.5f, roi.overlapFractionOf(RoiRect(0.4f, 0.1f, 0.2f, 0.2f)), 1e-4f)
        // A quarter: half in each axis.
        assertEquals(0.25f, roi.overlapFractionOf(RoiRect(0.4f, 0.4f, 0.2f, 0.2f)), 1e-4f)
        // Completely outside.
        assertEquals(0f, roi.overlapFractionOf(RoiRect(0.8f, 0.8f, 0.1f, 0.1f)), 1e-4f)
    }

    @Test
    fun `intersects agrees with overlap for touching and separated boxes`() {
        val roi = RoiRect(0.2f, 0.2f, 0.2f, 0.2f)
        assertTrue(roi.intersects(RoiRect(0.3f, 0.3f, 0.2f, 0.2f)))
        assertFalse(roi.intersects(RoiRect(0.4f, 0.4f, 0.2f, 0.2f)))
        assertFalse(roi.intersects(RoiRect(0.0f, 0.0f, 0.1f, 0.1f)))
    }
}
