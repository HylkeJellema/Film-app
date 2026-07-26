package com.kickercam.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The viewfinder's only piece of geometry.
 *
 * There is nothing to test about rotation any more because there is no rotation: the app is landscape,
 * the camera hands over landscape frames, and the view is given the frames' shape so that filling it
 * is a uniform scale. What is left to get wrong is the shape itself.
 */
class PreviewShapeTest {

    @Test
    fun `the view takes the frames' shape`() {
        assertEquals(16f / 9f, aspectRatioOf(1920, 1080), 1e-4f)
        assertEquals(4f / 3f, aspectRatioOf(1440, 1080), 1e-4f)
        assertEquals(16f / 9f, aspectRatioOf(3840, 2160), 1e-4f)
    }

    @Test
    fun `a missing format falls back to 16 by 9 rather than dividing by zero`() {
        assertEquals(16f / 9f, aspectRatioOf(0, 0), 1e-4f)
        assertEquals(16f / 9f, aspectRatioOf(1920, 0), 1e-4f)
        assertEquals(16f / 9f, aspectRatioOf(0, 1080), 1e-4f)
    }
}
