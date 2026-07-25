package com.kickercam.capture

import java.nio.ByteBuffer

/**
 * Fixed-size circular buffer of already-encoded media samples.
 *
 * Payload bytes live in one direct (off-heap) allocation addressed by a monotonically increasing
 * write position, so nothing is ever moved or re-allocated while recording. Sample metadata lives
 * in parallel arrays. A sample stays readable until the writer has lapped it, at which point it is
 * pruned from the front.
 *
 * Deliberately free of Android media types, so the wrap-around and key-frame-lookup logic can be
 * exercised by plain JVM unit tests — this is the one class where an off-by-one silently corrupts
 * every clip the app ever saves.
 *
 * Not thread safe — callers must serialise access (see [ClipRecorder]).
 */
class EncodedRing(val capacityBytes: Int, private val maxSamples: Int) {

    private val data: ByteBuffer = ByteBuffer.allocateDirect(capacityBytes)
    private val startPos = LongArray(maxSamples)
    private val sizes = IntArray(maxSamples)
    private val flags = IntArray(maxSamples)
    private val ptsUs = LongArray(maxSamples)

    private var head = 0
    private var writePos = 0L

    var size: Int = 0
        private set

    /** Number of samples dropped because the buffer lapped them. Diagnostics only. */
    var droppedSamples: Long = 0L
        private set

    fun clear() {
        head = 0
        size = 0
        writePos = 0L
        droppedSamples = 0L
    }

    fun ptsAt(index: Int): Long = ptsUs[slot(index)]

    fun sizeAt(index: Int): Int = sizes[slot(index)]

    fun flagsAt(index: Int): Int = flags[slot(index)]

    fun isKeyFrame(index: Int): Boolean =
        flags[slot(index)] and FLAG_KEY_FRAME != 0

    fun oldestPtsUs(): Long = if (size == 0) -1L else ptsAt(0)

    fun newestPtsUs(): Long = if (size == 0) -1L else ptsAt(size - 1)

    /** Seconds of media currently held. */
    fun spanUs(): Long = if (size < 2) 0L else newestPtsUs() - oldestPtsUs()

    private fun slot(index: Int): Int {
        require(index in 0 until size) { "index $index out of bounds (size=$size)" }
        return (head + index) % maxSamples
    }

    /**
     * Appends an encoded sample. Returns false when the sample is unusable — larger than the whole
     * buffer, or empty. The source buffer's position/limit are left untouched.
     */
    fun add(
        source: ByteBuffer,
        offset: Int,
        length: Int,
        sampleFlags: Int,
        presentationTimeUs: Long,
    ): Boolean {
        if (length <= 0 || length > capacityBytes) return false

        // Make room in the metadata arrays.
        if (size == maxSamples) {
            head = (head + 1) % maxSamples
            size--
            droppedSamples++
        }

        val target = (head + size) % maxSamples
        startPos[target] = writePos
        sizes[target] = length
        flags[target] = sampleFlags
        ptsUs[target] = presentationTimeUs

        val begin = (writePos % capacityBytes).toInt()
        val firstChunk = minOf(length, capacityBytes - begin)

        val src = source.duplicate()
        src.position(offset)
        src.limit(offset + firstChunk)
        data.position(begin)
        data.put(src)

        if (firstChunk < length) {
            val rest = source.duplicate()
            rest.position(offset + firstChunk)
            rest.limit(offset + length)
            data.position(0)
            data.put(rest)
        }

        writePos += length
        size++

        // Drop anything the write cursor has now overwritten.
        val oldestValidPos = writePos - capacityBytes
        while (size > 0 && startPos[head] < oldestValidPos) {
            head = (head + 1) % maxSamples
            size--
            droppedSamples++
        }
        return true
    }

    /** Copies sample [index] into [dest], leaving it positioned at 0 and limited to the sample size. */
    fun copyInto(index: Int, dest: ByteBuffer): Boolean {
        val s = slot(index)
        val length = sizes[s]
        if (dest.capacity() < length) return false

        val begin = (startPos[s] % capacityBytes).toInt()
        val firstChunk = minOf(length, capacityBytes - begin)

        dest.clear()
        val first = data.duplicate()
        first.position(begin)
        first.limit(begin + firstChunk)
        dest.put(first)

        if (firstChunk < length) {
            val second = data.duplicate()
            second.position(0)
            second.limit(length - firstChunk)
            dest.put(second)
        }

        dest.flip()
        return true
    }

    fun largestSampleSize(): Int {
        var max = 0
        for (i in 0 until size) max = maxOf(max, sizeAt(i))
        return max
    }

    /**
     * Index of the newest key frame at or before [targetPtsUs]. Falls back to the oldest key frame
     * held when the requested moment is older than the buffer. Returns -1 when there is no key
     * frame at all.
     */
    fun keyFrameIndexAtOrBefore(targetPtsUs: Long): Int {
        for (i in size - 1 downTo 0) {
            if (ptsAt(i) <= targetPtsUs && isKeyFrame(i)) return i
        }
        for (i in 0 until size) {
            if (isKeyFrame(i)) return i
        }
        return -1
    }

    /** Index of the first sample with pts >= [targetPtsUs], or [size] when none. */
    fun firstIndexAtOrAfter(targetPtsUs: Long): Int {
        for (i in 0 until size) {
            if (ptsAt(i) >= targetPtsUs) return i
        }
        return size
    }

    companion object {
        /** Mirrors `MediaCodec.BUFFER_FLAG_KEY_FRAME`; kept local so this class stays Android-free. */
        const val FLAG_KEY_FRAME = 1
    }
}
