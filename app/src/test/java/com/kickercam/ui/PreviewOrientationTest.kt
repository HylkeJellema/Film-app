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

    // ------------------------------------------------------------------ preview shape

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
    fun `an unrotated 1080p preview keeps its 16 by 9 shape`() {
        assertEquals(16f / 9f, previewAspectRatio(1920, 1080, 0), 1e-4f)
        assertEquals(16f / 9f, previewAspectRatio(1920, 1080, 180), 1e-4f)
    }

    @Test
    fun `a quarter turn transposes the preview shape`() {
        // A 16:9 buffer rotated 90° occupies a 9:16 slot. Reporting 16:9 here is what let the image
        // be stretched across a landscape window.
        assertEquals(9f / 16f, previewAspectRatio(1920, 1080, 90), 1e-4f)
        assertEquals(9f / 16f, previewAspectRatio(1920, 1080, 270), 1e-4f)
    }

    @Test
    fun `four by three buffers are handled too`() {
        assertEquals(4f / 3f, previewAspectRatio(1440, 1080, 0), 1e-4f)
        assertEquals(3f / 4f, previewAspectRatio(1440, 1080, 90), 1e-4f)
    }

    @Test
    fun `a degenerate size falls back to 16 by 9 instead of dividing by zero`() {
        assertEquals(16f / 9f, bufferAspectRatio(0, 0), 1e-4f)
        assertEquals(16f / 9f, bufferAspectRatio(1920, 0), 1e-4f)
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
