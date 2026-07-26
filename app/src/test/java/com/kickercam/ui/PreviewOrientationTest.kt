package com.kickercam.ui

import android.view.Surface
import com.kickercam.camera.Camera2Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val LENS_FACING_FRONT = 0
private const val LENS_FACING_BACK = 1

/**
 * Orientation is where the viewfinder went wrong: quarter turns were discarded instead of applied, so
 * the image lay on its side. These lock down the two halves of the fix — the rotation the camera
 * frames need, and the shape the rotated preview occupies on screen.
 */
class PreviewOrientationTest {

    private fun backRotation(sensorOrientation: Int, deviceDegrees: Int) =
        Camera2Session.displayRotationDegrees(sensorOrientation, deviceDegrees, LENS_FACING_BACK)

    // ------------------------------------------------------------------ camera rotation

    @Test
    fun `a landscape-held phone needs no rotation for a 90 degree sensor`() {
        // The usual case: back sensor mounted at 90°, phone on its side in the normal landscape.
        // The sensor's long axis already lines up with the screen, so the answer must be zero —
        // anything else rotates the saved file into portrait.
        assertEquals(0, backRotation(sensorOrientation = 90, deviceDegrees = 90))
    }

    @Test
    fun `the other landscape is a half turn`() {
        assertEquals(180, backRotation(sensorOrientation = 90, deviceDegrees = 270))
    }

    @Test
    fun `holding the phone upright needs a quarter turn`() {
        assertEquals(90, backRotation(sensorOrientation = 90, deviceDegrees = 0))
        assertEquals(270, backRotation(sensorOrientation = 90, deviceDegrees = 180))
    }

    @Test
    fun `front cameras rotate the other way`() {
        assertEquals(
            180,
            Camera2Session.displayRotationDegrees(90, 90, LENS_FACING_FRONT),
        )
        assertEquals(
            0,
            Camera2Session.displayRotationDegrees(90, 270, LENS_FACING_FRONT),
        )
    }

    @Test
    fun `results always land on a normalised quarter turn`() {
        for (sensor in listOf(0, 90, 180, 270)) {
            for (device in listOf(0, 90, 180, 270)) {
                val rotation = backRotation(sensor, device)
                assertTrue("$sensor/$device -> $rotation", rotation in listOf(0, 90, 180, 270))
            }
        }
    }

    // ------------------------------------------------------------------ device orientation mapping

    @Test
    fun `surface rotations convert to degrees`() {
        assertEquals(0, surfaceRotationToDegrees(Surface.ROTATION_0))
        assertEquals(90, surfaceRotationToDegrees(Surface.ROTATION_90))
        assertEquals(180, surfaceRotationToDegrees(Surface.ROTATION_180))
        assertEquals(270, surfaceRotationToDegrees(Surface.ROTATION_270))
    }

    // ------------------------------------------------------------------ preview placement

    @Test
    fun `quarter turns are recognised including unnormalised input`() {
        assertTrue(isQuarterTurn(90))
        assertTrue(isQuarterTurn(270))
        assertTrue(isQuarterTurn(-90))
        assertTrue(isQuarterTurn(450))
        assertFalse(isQuarterTurn(0))
        assertFalse(isQuarterTurn(180))
        assertFalse(isQuarterTurn(360))
    }

    @Test
    fun `an unrotated 1080p buffer fills a 16 by 9 viewfinder exactly`() {
        val fit = previewFit(2560f, 1440f, 1920, 1080, 0)!!
        assertEquals(0f, fit.left, 1e-3f)
        assertEquals(0f, fit.top, 1e-3f)
        assertEquals(2560f, fit.width, 1e-3f)
        assertEquals(1440f, fit.height, 1e-3f)
    }

    @Test
    fun `a wider screen gets bars at the sides, never a stretched image`() {
        // The 19.5:9 phone this was written for. The image keeps its shape and the rest stays black.
        val fit = previewFit(3120f, 1440f, 1920, 1080, 0)!!
        assertEquals(1440f, fit.height, 1e-3f)
        assertEquals(2560f, fit.width, 1e-3f)
        assertEquals(280f, fit.left, 1e-3f)
        assertEquals(0f, fit.top, 1e-3f)
        assertEquals(16f / 9f, fit.width / fit.height, 1e-3f)
    }

    @Test
    fun `a quarter turn in portrait gets bars above and below`() {
        // 1440x3120 portrait window, landscape buffer turned upright: 1440x2560 centred vertically.
        // The old layout clamped this to a 1440x1440 square, which is what "stretched into a square
        // box" was.
        val fit = previewFit(1440f, 3120f, 1920, 1080, 90)!!
        assertEquals(1440f, fit.width, 1e-3f)
        assertEquals(2560f, fit.height, 1e-3f)
        assertEquals(0f, fit.left, 1e-3f)
        assertEquals(280f, fit.top, 1e-3f)
    }

    @Test
    fun `a quarter turn in landscape gets bars at the sides`() {
        val fit = previewFit(3120f, 1440f, 1920, 1080, 270)!!
        assertEquals(810f, fit.width, 1e-3f)
        assertEquals(1440f, fit.height, 1e-3f)
        assertEquals(1155f, fit.left, 1e-3f)
        assertEquals(0f, fit.top, 1e-3f)
    }

    @Test
    fun `the image is never distorted, whatever the buffer, screen and rotation`() {
        val buffers = listOf(1920 to 1080, 1440 to 1080, 3840 to 2160)
        val views = listOf(3120f to 1440f, 1440f to 3120f, 2000f to 2000f, 1080f to 2400f)
        for ((bufferWidth, bufferHeight) in buffers) {
            for ((viewWidth, viewHeight) in views) {
                for (rotation in listOf(0, 90, 180, 270)) {
                    val fit = previewFit(viewWidth, viewHeight, bufferWidth, bufferHeight, rotation)!!
                    val expected = if (isQuarterTurn(rotation)) {
                        bufferHeight.toFloat() / bufferWidth.toFloat()
                    } else {
                        bufferWidth.toFloat() / bufferHeight.toFloat()
                    }
                    val label = "$bufferWidth x $bufferHeight in $viewWidth x $viewHeight @ $rotation"
                    assertEquals(label, expected, fit.width / fit.height, 1e-3f)
                    // Inside the viewfinder, and centred.
                    assertTrue(label, fit.width <= viewWidth + 1e-3f)
                    assertTrue(label, fit.height <= viewHeight + 1e-3f)
                    assertEquals(label, viewWidth - fit.right, fit.left, 1e-3f)
                    assertEquals(label, viewHeight - fit.bottom, fit.top, 1e-3f)
                }
            }
        }
    }

    @Test
    fun `at least one axis always touches the edge, so nothing is wasted`() {
        for (rotation in listOf(0, 90, 180, 270)) {
            val fit = previewFit(3120f, 1440f, 1920, 1080, rotation)!!
            val touches = fit.width in 3119f..3121f || fit.height in 1439f..1441f
            assertTrue("rotation $rotation -> $fit", touches)
        }
    }

    @Test
    fun `a degenerate size has no answer rather than a wrong one`() {
        assertEquals(null, previewFit(0f, 1440f, 1920, 1080, 0))
        assertEquals(null, previewFit(3120f, 1440f, 0, 0, 0))
        assertEquals(null, previewFit(3120f, 0f, 1920, 1080, 90))
    }

    // ------------------------------------------------------------------ rotation correction

    private fun corrected(offset: Int, sensorOrientation: Int, deviceDegrees: Int) =
        Camera2Session.effectiveRotationDegrees(offset, sensorOrientation, deviceDegrees, LENS_FACING_BACK)

    @Test
    fun `no correction leaves the derived result alone`() {
        for (device in listOf(0, 90, 180, 270)) {
            assertEquals(backRotation(90, device), corrected(0, 90, device))
        }
    }

    @Test
    fun `a correction follows the phone instead of pinning one angle`() {
        // The device that prompted this reports SENSOR_ORIENTATION 90 but delivers frames as though it
        // were 270: half a turn out in every orientation. Portrait needed 270, which a fixed 270 gets
        // right — and then landscape needs 180, which it cannot give. Half a turn added to the
        // derivation is right in both.
        assertEquals(270, corrected(180, sensorOrientation = 90, deviceDegrees = 0))
        assertEquals(180, corrected(180, sensorOrientation = 90, deviceDegrees = 90))
        assertEquals(90, corrected(180, sensorOrientation = 90, deviceDegrees = 180))
        assertEquals(0, corrected(180, sensorOrientation = 90, deviceDegrees = 270))
    }

    @Test
    fun `corrections stay on a normalised quarter turn`() {
        for (offset in listOf(0, 90, 180, 270)) {
            for (device in listOf(0, 90, 180, 270)) {
                val rotation = corrected(offset, 90, device)
                assertTrue("$offset/$device -> $rotation", rotation in listOf(0, 90, 180, 270))
            }
        }
    }

    @Test
    fun `four corrections bring you back to where you started`() {
        assertEquals(corrected(0, 90, 90), corrected(360, 90, 90))
    }
}
