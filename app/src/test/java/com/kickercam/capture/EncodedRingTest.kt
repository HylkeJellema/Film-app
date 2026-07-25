package com.kickercam.capture

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * The rolling buffer is what makes "save the five seconds before I hit the kicker" possible, so its
 * wrap-around and key-frame lookup are worth testing directly rather than discovering on the water.
 */
class EncodedRingTest {

    private fun sample(byte: Int, length: Int): ByteBuffer =
        ByteBuffer.allocate(length).apply {
            repeat(length) { put(byte.toByte()) }
            flip()
        }

    private fun EncodedRing.push(
        marker: Int,
        length: Int,
        ptsUs: Long,
        keyFrame: Boolean = false,
    ): Boolean = add(
        source = sample(marker, length),
        offset = 0,
        length = length,
        sampleFlags = if (keyFrame) EncodedRing.FLAG_KEY_FRAME else 0,
        presentationTimeUs = ptsUs,
    )

    private fun EncodedRing.readBytes(index: Int): ByteArray {
        val dest = ByteBuffer.allocate(sizeAt(index))
        assertTrue("copyInto failed for index $index", copyInto(index, dest))
        val out = ByteArray(dest.remaining())
        dest.get(out)
        return out
    }

    @Test
    fun `stores and returns samples in order`() {
        val ring = EncodedRing(capacityBytes = 1024, maxSamples = 8)

        assertTrue(ring.push(marker = 1, length = 10, ptsUs = 100, keyFrame = true))
        assertTrue(ring.push(marker = 2, length = 20, ptsUs = 200))
        assertTrue(ring.push(marker = 3, length = 30, ptsUs = 300))

        assertEquals(3, ring.size)
        assertEquals(100L, ring.oldestPtsUs())
        assertEquals(300L, ring.newestPtsUs())
        assertEquals(200L, ring.spanUs())

        assertArrayEquals(ByteArray(10) { 1 }, ring.readBytes(0))
        assertArrayEquals(ByteArray(20) { 2 }, ring.readBytes(1))
        assertArrayEquals(ByteArray(30) { 3 }, ring.readBytes(2))

        assertTrue(ring.isKeyFrame(0))
        assertFalse(ring.isKeyFrame(1))
    }

    @Test
    fun `rejects samples that cannot possibly fit`() {
        val ring = EncodedRing(capacityBytes = 64, maxSamples = 4)
        assertFalse(ring.push(marker = 1, length = 65, ptsUs = 0))
        assertFalse(ring.push(marker = 1, length = 0, ptsUs = 0))
        assertEquals(0, ring.size)
    }

    @Test
    fun `drops the oldest sample when the metadata slots run out`() {
        val ring = EncodedRing(capacityBytes = 4096, maxSamples = 3)

        ring.push(marker = 1, length = 8, ptsUs = 100)
        ring.push(marker = 2, length = 8, ptsUs = 200)
        ring.push(marker = 3, length = 8, ptsUs = 300)
        ring.push(marker = 4, length = 8, ptsUs = 400)

        assertEquals(3, ring.size)
        assertEquals(200L, ring.oldestPtsUs())
        assertEquals(400L, ring.newestPtsUs())
        assertEquals(1L, ring.droppedSamples)
        assertArrayEquals(ByteArray(8) { 2 }, ring.readBytes(0))
    }

    @Test
    fun `payload wraps around the end of the buffer intact`() {
        // 100 bytes of capacity, 30-byte samples: the fourth sample must straddle the wrap point.
        val ring = EncodedRing(capacityBytes = 100, maxSamples = 16)

        for (i in 1..4) {
            assertTrue(ring.push(marker = i, length = 30, ptsUs = i * 100L))
        }

        // Sample 1 occupied bytes 0..29 and has been lapped by sample 4 (bytes 90..119 -> 90..99 + 0..19).
        assertEquals(3, ring.size)
        assertEquals(200L, ring.oldestPtsUs())

        // The split sample must read back byte-for-byte across the seam.
        assertArrayEquals(ByteArray(30) { 4 }, ring.readBytes(ring.size - 1))
        assertArrayEquals(ByteArray(30) { 2 }, ring.readBytes(0))
        assertArrayEquals(ByteArray(30) { 3 }, ring.readBytes(1))
    }

    @Test
    fun `survives many wraps and always returns the newest samples`() {
        val ring = EncodedRing(capacityBytes = 1000, maxSamples = 32)

        for (i in 1..500) {
            ring.push(marker = i % 256, length = 37, ptsUs = i * 1000L)
        }

        // Everything still held must be readable and contiguous in pts.
        assertTrue(ring.size > 0)
        var previous = -1L
        for (i in 0 until ring.size) {
            val pts = ring.ptsAt(i)
            assertTrue("pts must increase", pts > previous)
            previous = pts
            val expected = ((pts / 1000L).toInt() % 256).toByte()
            assertArrayEquals(ByteArray(37) { expected }, ring.readBytes(i))
        }
        assertEquals(500_000L, ring.newestPtsUs())
    }

    @Test
    fun `finds the newest key frame at or before a moment`() {
        val ring = EncodedRing(capacityBytes = 8192, maxSamples = 32)

        ring.push(marker = 1, length = 8, ptsUs = 1_000, keyFrame = true)
        ring.push(marker = 2, length = 8, ptsUs = 2_000)
        ring.push(marker = 3, length = 8, ptsUs = 3_000, keyFrame = true)
        ring.push(marker = 4, length = 8, ptsUs = 4_000)
        ring.push(marker = 5, length = 8, ptsUs = 5_000)

        // Exactly on a key frame.
        assertEquals(2, ring.keyFrameIndexAtOrBefore(3_000))
        // Between key frames: must step back, never forward, or the clip starts undecodable.
        assertEquals(2, ring.keyFrameIndexAtOrBefore(4_500))
        assertEquals(0, ring.keyFrameIndexAtOrBefore(2_999))
        // Older than anything buffered: fall back to the oldest key frame we still have.
        assertEquals(0, ring.keyFrameIndexAtOrBefore(-5_000))
    }

    @Test
    fun `reports no key frame when none was ever written`() {
        val ring = EncodedRing(capacityBytes = 1024, maxSamples = 8)
        ring.push(marker = 1, length = 8, ptsUs = 1_000)
        assertEquals(-1, ring.keyFrameIndexAtOrBefore(1_000))
    }

    @Test
    fun `firstIndexAtOrAfter locates the audio start for a clip`() {
        val ring = EncodedRing(capacityBytes = 1024, maxSamples = 16)
        ring.push(marker = 1, length = 8, ptsUs = 1_000)
        ring.push(marker = 2, length = 8, ptsUs = 2_000)
        ring.push(marker = 3, length = 8, ptsUs = 3_000)

        assertEquals(0, ring.firstIndexAtOrAfter(500))
        assertEquals(1, ring.firstIndexAtOrAfter(2_000))
        assertEquals(2, ring.firstIndexAtOrAfter(2_001))
        assertEquals(3, ring.firstIndexAtOrAfter(9_999))
    }

    @Test
    fun `honours the source buffer offset and leaves it untouched`() {
        val ring = EncodedRing(capacityBytes = 1024, maxSamples = 8)
        val source = ByteBuffer.allocate(64)
        repeat(16) { source.put(0xEE.toByte()) }
        repeat(16) { source.put(0x7A.toByte()) }
        source.position(3)
        source.limit(40)

        assertTrue(
            ring.add(
                source = source,
                offset = 16,
                length = 16,
                sampleFlags = EncodedRing.FLAG_KEY_FRAME,
                presentationTimeUs = 42,
            ),
        )

        assertArrayEquals(ByteArray(16) { 0x7A }, ring.readBytes(0))
        assertEquals("position must not move", 3, source.position())
        assertEquals("limit must not move", 40, source.limit())
    }

    @Test
    fun `clear resets the buffer for a new session`() {
        val ring = EncodedRing(capacityBytes = 512, maxSamples = 8)
        ring.push(marker = 1, length = 8, ptsUs = 1_000, keyFrame = true)
        ring.clear()

        assertEquals(0, ring.size)
        assertEquals(-1L, ring.oldestPtsUs())
        assertEquals(0L, ring.spanUs())
        assertTrue(ring.push(marker = 2, length = 8, ptsUs = 5_000, keyFrame = true))
        assertEquals(1, ring.size)
    }
}
