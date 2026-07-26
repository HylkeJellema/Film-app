package com.kickercam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Choosing the recording size is the one place a shape mismatch could creep back in, so the rule is
 * pinned down: every option is the sensor's own shape, and a request outside that set is refused
 * rather than passed through.
 */
class VideoSizeChoiceTest {

    /** What a 16:9-advertising phone typically lists, with its 4:3 sizes mixed in as they really are. */
    private val advertised = listOf(
        3840 to 2160,
        2560 to 1440,
        1920 to 1080,
        1440 to 1080,
        1280 to 720,
        640 to 480,
    )

    @Test
    fun `only the sensor's own shape is offered`() {
        val options = sameShapeSizes(advertised)
        assertEquals(listOf(3840 to 2160, 2560 to 1440, 1920 to 1080, 1280 to 720), options)
        for ((width, height) in options) {
            assertEquals("${width}x$height", 16f / 9f, width.toFloat() / height.toFloat(), 0.02f)
        }
    }

    @Test
    fun `a four by three sensor offers its own shape instead`() {
        val fourByThree = listOf(2048 to 1536, 1920 to 1080, 1440 to 1080, 640 to 480)
        assertEquals(listOf(2048 to 1536, 1440 to 1080, 640 to 480), sameShapeSizes(fourByThree))
    }

    @Test
    fun `no request lands on the largest size up to 1080p`() {
        assertEquals(1920 to 1080, chooseSize(advertised, null))
    }

    @Test
    fun `an advertised request is honoured, including 4K`() {
        assertEquals(3840 to 2160, chooseSize(advertised, 3840 to 2160))
        assertEquals(2560 to 1440, chooseSize(advertised, 2560 to 1440))
        assertEquals(1280 to 720, chooseSize(advertised, 1280 to 720))
    }

    @Test
    fun `a request in the wrong shape is refused, not passed through`() {
        // 1440x1080 is advertised, but it is 4:3 on a 16:9 sensor. Honouring it is exactly the bug that
        // put a shape on screen the sensor does not stream.
        assertEquals(1920 to 1080, chooseSize(advertised, 1440 to 1080))
    }

    @Test
    fun `a size this camera has never heard of is refused`() {
        assertEquals(1920 to 1080, chooseSize(advertised, 2560 to 1080))
        assertEquals(1920 to 1080, chooseSize(advertised, 9999 to 9999))
    }

    @Test
    fun `a camera with nothing above 720p still answers`() {
        val small = listOf(1280 to 720, 640 to 360)
        assertEquals(1280 to 720, chooseSize(small, null))
        assertTrue(sameShapeSizes(small).isNotEmpty())
    }

    @Test
    fun `an empty list falls back rather than failing`() {
        assertEquals(1920 to 1080, chooseSize(emptyList(), null))
    }
}
