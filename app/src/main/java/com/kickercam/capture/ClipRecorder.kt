package com.kickercam.capture

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToLong

private const val TAG = "ClipRecorder"

data class RecorderConfig(
    val widthPx: Int,
    val heightPx: Int,
    val fps: Int,
    val bitrateBps: Int,
    val mime: String,
    val preRollUs: Long,
    val postRollUs: Long,
    val maxClipUs: Long,
    val audioEnabled: Boolean,
    val orientationHint: Int,
    val outputDir: File,
    val cameraTimestampIsRealtime: Boolean,
) {
    /** Key frames land every second, so the saved pre-roll snaps back to at most this much extra. */
    val iFrameIntervalSec: Int = 1
}

data class ClipResult(
    val file: File,
    val durationMs: Long,
    /** Where inside the clip the detection happened. */
    val momentOffsetMs: Long,
    val actualPreRollMs: Long,
    val triggerReason: String,
    val widthPx: Int,
    val heightPx: Int,
    val fps: Int,
    val hasAudio: Boolean,
)

data class RecorderStats(
    val bufferedSec: Float = 0f,
    val bufferFillFraction: Float = 0f,
    val droppedSamples: Long = 0L,
    val isSavingClip: Boolean = false,
    val currentClipElapsedMs: Long = 0L,
    val encoderRunning: Boolean = false,
)

/**
 * Encodes the camera stream continuously into a rolling in-memory buffer and, when [trigger] fires,
 * muxes out `preRoll + postRoll` seconds around that moment.
 *
 * All buffer mutation happens under [lock]; the video drain thread, the audio thread and trigger
 * callers all serialise on it.
 */
class ClipRecorder(
    private val config: RecorderConfig,
    private val listener: Listener,
) {

    interface Listener {
        fun onClipStarted(reason: String)
        fun onClipFinished(result: ClipResult)
        fun onRecorderError(message: String, cause: Throwable?)
    }

    private val lock = Any()

    private var videoCodec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var videoThread: Thread? = null
    @Volatile private var running = false

    private var audioCodec: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null

    private lateinit var videoRing: EncodedRing
    private var audioRing: EncodedRing? = null

    private var videoFormat: MediaFormat? = null
    private var audioFormat: MediaFormat? = null

    private var active: ActiveClip? = null
    private var scratch: ByteBuffer = ByteBuffer.allocateDirect(1 shl 20)

    private var audioAnchorUs = -1L
    private var audioFramesQueued = 0L

    private var droppedAudioSamples = 0L

    /** Which clock the camera actually stamps frames with, measured from the first video sample. */
    @Volatile private var cameraClockIsBootTime = false
    @Volatile private var cameraClockDetected = false

    /**
     * Orientation written into the clip's container. Read when a clip starts rather than fixed at
     * construction, so re-mounting the phone does not require tearing the session down.
     */
    @Volatile var orientationHint: Int = config.orientationHint

    val estimatedVideoBufferBytes: Int = videoRingCapacity()

    private class ActiveClip(
        val muxer: MediaMuxer,
        val file: File,
        val videoTrack: Int,
        val audioTrack: Int,
        val basePtsUs: Long,
        val momentPtsUs: Long,
        val hardEndPtsUs: Long,
        val reason: String,
    ) {
        var endPtsUs: Long = 0L
        var lastVideoPtsUs: Long = basePtsUs
        var videoSamples: Int = 0
        var audioSamples: Int = 0
    }

    // ------------------------------------------------------------------ lifecycle

    /** Starts the encoder and returns the surface the camera should render into. */
    fun start(): Surface {
        synchronized(lock) {
            require(!running) { "already started" }

            videoRing = EncodedRing(videoRingCapacity(), videoRingSampleSlots())
            audioRing = if (config.audioEnabled) {
                EncodedRing(audioRingCapacity(), audioRingSampleSlots())
            } else {
                null
            }

            val format = MediaFormat.createVideoFormat(config.mime, config.widthPx, config.heightPx).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, config.bitrateBps)
                setInteger(MediaFormat.KEY_FRAME_RATE, config.fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, config.iFrameIntervalSec)
                setInteger(
                    MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
                )
                // Ask for realtime scheduling so high frame rates are actually reachable.
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                setFloat(MediaFormat.KEY_OPERATING_RATE, config.fps.toFloat())
            }

            // Configure can reject the format outright (4K120, an unsupported profile). Release the
            // half-built codec rather than leaking it, and let the caller fall back.
            val codec = MediaCodec.createEncoderByType(config.mime)
            val surface = try {
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.createInputSurface().also { codec.start() }
            } catch (t: Throwable) {
                runCatching { codec.release() }
                throw t
            }

            videoCodec = codec
            inputSurface = surface
            running = true

            videoThread = Thread({ drainVideoLoop(codec) }, "kc-video-drain").also { it.start() }

            if (config.audioEnabled) {
                startAudio()
            }
            return surface
        }
    }

    fun stop() {
        val threadsToJoin: List<Thread>
        synchronized(lock) {
            if (!running) return
            running = false
            threadsToJoin = listOfNotNull(videoThread, audioThread)
        }

        threadsToJoin.forEach { runCatching { it.join(1500) } }

        synchronized(lock) {
            active?.let { finishClipLocked(it, aborted = true) }

            runCatching { audioRecord?.stop() }
            runCatching { audioRecord?.release() }
            audioRecord = null
            runCatching { audioCodec?.stop() }
            runCatching { audioCodec?.release() }
            audioCodec = null

            runCatching { videoCodec?.stop() }
            runCatching { videoCodec?.release() }
            videoCodec = null
            runCatching { inputSurface?.release() }
            inputSurface = null

            videoThread = null
            audioThread = null
            videoFormat = null
            audioFormat = null
            audioAnchorUs = -1L
            audioFramesQueued = 0L
            cameraClockDetected = false
            droppedAudioSamples = 0L
        }
    }

    fun stats(): RecorderStats = synchronized(lock) {
        if (!this::videoRing.isInitialized) return RecorderStats()
        val clip = active
        RecorderStats(
            bufferedSec = videoRing.spanUs() / 1_000_000f,
            bufferFillFraction = (videoRing.spanUs().toFloat() /
                (config.preRollUs + 2_000_000L).toFloat()).coerceIn(0f, 1f),
            droppedSamples = videoRing.droppedSamples,
            isSavingClip = clip != null,
            currentClipElapsedMs = clip?.let { (it.lastVideoPtsUs - it.basePtsUs) / 1000 } ?: 0L,
            encoderRunning = running,
        )
    }

    fun bufferedSpanUs(): Long = synchronized(lock) {
        if (this::videoRing.isInitialized) videoRing.spanUs() else 0L
    }

    // ------------------------------------------------------------------ triggering

    /**
     * Marks an instant as interesting. Starts a clip, or extends the one in flight so a rider taking
     * two hits in a row lands in a single video.
     *
     * @param momentPtsUs the sensor timestamp of the frame that fired, in microseconds. Passing the
     *   detecting frame's own timestamp keeps the clip centred on the action even though the
     *   detector needed tens of milliseconds to reach its verdict. Defaults to the newest frame
     *   encoded so far.
     */
    fun trigger(reason: String, momentPtsUs: Long? = null) {
        synchronized(lock) {
            if (!running) return
            if (!this::videoRing.isInitialized || videoRing.size == 0) return

            // Clamp into what is actually buffered; a stale or future timestamp must not skew the clip.
            val moment = (momentPtsUs ?: videoRing.newestPtsUs())
                .coerceIn(videoRing.oldestPtsUs(), videoRing.newestPtsUs())

            val clip = active
            if (clip != null) {
                clip.endPtsUs = minOf(moment + config.postRollUs, clip.hardEndPtsUs)
                return
            }
            startClipLocked(moment, reason)
        }
    }

    private fun startClipLocked(momentPtsUs: Long, reason: String) {
        val vFormat = videoFormat
        if (vFormat == null) {
            Log.w(TAG, "trigger ignored: encoder format not ready yet")
            return
        }

        val requestedStart = momentPtsUs - config.preRollUs
        val keyIndex = videoRing.keyFrameIndexAtOrBefore(requestedStart)
        if (keyIndex < 0) {
            Log.w(TAG, "trigger ignored: no key frame buffered yet")
            return
        }
        val basePts = videoRing.ptsAt(keyIndex)

        if (!config.outputDir.exists()) config.outputDir.mkdirs()
        val file = File(config.outputDir, "clip_${System.currentTimeMillis()}.mp4")

        val muxer: MediaMuxer
        val videoTrack: Int
        var audioTrack = -1
        try {
            muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer.setOrientationHint(orientationHint)
            videoTrack = muxer.addTrack(vFormat)
            audioFormat?.let { audioTrack = muxer.addTrack(it) }
            muxer.start()
        } catch (t: Throwable) {
            listener.onRecorderError("Could not start clip file", t)
            file.delete()
            return
        }

        val clip = ActiveClip(
            muxer = muxer,
            file = file,
            videoTrack = videoTrack,
            audioTrack = audioTrack,
            basePtsUs = basePts,
            momentPtsUs = momentPtsUs,
            hardEndPtsUs = basePts + config.maxClipUs,
            reason = reason,
        )
        clip.endPtsUs = minOf(momentPtsUs + config.postRollUs, clip.hardEndPtsUs)
        active = clip

        writePreRollLocked(clip, keyIndex)
        listener.onClipStarted(reason)
    }

    /** Drains everything already buffered into the muxer, interleaving video and audio by pts. */
    private fun writePreRollLocked(clip: ActiveClip, videoStartIndex: Int) {
        val aRing = audioRing
        var vi = videoStartIndex
        var ai = if (aRing != null && clip.audioTrack >= 0) {
            aRing.firstIndexAtOrAfter(clip.basePtsUs)
        } else {
            Int.MAX_VALUE
        }

        val videoEnd = videoRing.size
        val audioEnd = aRing?.size ?: 0

        while (vi < videoEnd || (ai < audioEnd)) {
            val vPts = if (vi < videoEnd) videoRing.ptsAt(vi) else Long.MAX_VALUE
            val aPts = if (ai < audioEnd) aRing!!.ptsAt(ai) else Long.MAX_VALUE
            if (vPts <= aPts) {
                writeFromRingLocked(clip, videoRing, vi, clip.videoTrack, isVideo = true)
                vi++
            } else {
                writeFromRingLocked(clip, aRing!!, ai, clip.audioTrack, isVideo = false)
                ai++
            }
        }
    }

    private fun writeFromRingLocked(
        clip: ActiveClip,
        ring: EncodedRing,
        index: Int,
        track: Int,
        isVideo: Boolean,
    ) {
        val length = ring.sizeAt(index)
        ensureScratch(length)
        if (!ring.copyInto(index, scratch)) return

        val info = MediaCodec.BufferInfo().apply {
            set(0, length, (ring.ptsAt(index) - clip.basePtsUs).coerceAtLeast(0L), ring.flagsAt(index))
        }
        try {
            clip.muxer.writeSampleData(track, scratch, info)
            if (isVideo) {
                clip.videoSamples++
                clip.lastVideoPtsUs = ring.ptsAt(index)
            } else {
                clip.audioSamples++
            }
        } catch (t: Throwable) {
            Log.w(TAG, "writeSampleData (buffered) failed", t)
        }
    }

    private fun ensureScratch(length: Int) {
        if (scratch.capacity() < length) {
            scratch = ByteBuffer.allocateDirect(Integer.highestOneBit(length) * 2)
        }
    }

    private fun finishClipLocked(clip: ActiveClip, aborted: Boolean) {
        active = null
        runCatching { clip.muxer.stop() }.onFailure { Log.w(TAG, "muxer.stop failed", it) }
        runCatching { clip.muxer.release() }

        if (clip.videoSamples < 2) {
            clip.file.delete()
            if (!aborted) listener.onRecorderError("Clip contained no frames", null)
            return
        }

        val durationMs = (clip.lastVideoPtsUs - clip.basePtsUs) / 1000
        listener.onClipFinished(
            ClipResult(
                file = clip.file,
                durationMs = durationMs,
                momentOffsetMs = ((clip.momentPtsUs - clip.basePtsUs) / 1000).coerceIn(0L, durationMs),
                actualPreRollMs = (clip.momentPtsUs - clip.basePtsUs) / 1000,
                triggerReason = clip.reason,
                widthPx = config.widthPx,
                heightPx = config.heightPx,
                fps = config.fps,
                hasAudio = clip.audioTrack >= 0 && clip.audioSamples > 0,
            ),
        )
    }

    // ------------------------------------------------------------------ video drain

    private fun drainVideoLoop(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                val index = codec.dequeueOutputBuffer(info, 10_000L)
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> continue
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        synchronized(lock) { videoFormat = codec.outputFormat }
                    }
                    index >= 0 -> {
                        val buffer = codec.getOutputBuffer(index)
                        if (buffer != null && info.size > 0 &&
                            info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                        ) {
                            synchronized(lock) { onVideoSampleLocked(buffer, info) }
                        }
                        codec.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } catch (t: Throwable) {
            if (running) {
                Log.e(TAG, "video drain failed", t)
                listener.onRecorderError("Video encoder stopped: ${t.message}", t)
            }
        }
    }

    private fun onVideoSampleLocked(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (!cameraClockDetected) detectCameraClockLocked(info.presentationTimeUs)
        videoRing.add(buffer, info.offset, info.size, info.flags, info.presentationTimeUs)

        val clip = active ?: return
        if (info.presentationTimeUs < clip.basePtsUs) return

        val out = MediaCodec.BufferInfo().apply {
            set(
                info.offset,
                info.size,
                (info.presentationTimeUs - clip.basePtsUs).coerceAtLeast(0L),
                info.flags,
            )
        }
        try {
            clip.muxer.writeSampleData(clip.videoTrack, buffer, out)
            clip.videoSamples++
            clip.lastVideoPtsUs = info.presentationTimeUs
        } catch (t: Throwable) {
            Log.w(TAG, "writeSampleData (live video) failed", t)
        }

        if (info.presentationTimeUs >= clip.endPtsUs) {
            finishClipLocked(clip, aborted = false)
        }
    }

    // ------------------------------------------------------------------ audio

    /**
     * Audio has to be stamped in the same time domain the camera uses, and the camera's declared
     * [android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE] cannot be
     * trusted — some devices advertise REALTIME while emitting monotonic timestamps.
     *
     * The two clocks differ by however long the phone has spent in deep sleep since boot, which on a
     * phone that has been alive for days is *hours*. Getting it wrong therefore does not cause a
     * subtle sync error: it writes an audio sample hours past the video, and the muxed file claims a
     * duration of hours with a single frozen frame.
     *
     * So it is measured rather than assumed. The first encoded video sample's timestamp is compared
     * against both candidate clocks and the nearer one wins; they are hours apart, so the comparison
     * is never ambiguous.
     */
    private fun detectCameraClockLocked(firstVideoPtsUs: Long) {
        val monotonicUs = System.nanoTime() / 1000
        val bootTimeUs = SystemClock.elapsedRealtimeNanos() / 1000

        val monotonicDelta = abs(firstVideoPtsUs - monotonicUs)
        val bootTimeDelta = abs(firstVideoPtsUs - bootTimeUs)
        cameraClockIsBootTime = bootTimeDelta < monotonicDelta
        cameraClockDetected = true

        Log.i(
            TAG,
            "camera clock detected as ${if (cameraClockIsBootTime) "BOOTTIME" else "MONOTONIC"} " +
                "(declared ${if (config.cameraTimestampIsRealtime) "REALTIME" else "UNKNOWN"}, " +
                "offset ${minOf(monotonicDelta, bootTimeDelta) / 1000}ms)",
        )
    }

    private fun cameraClockUs(): Long =
        if (cameraClockIsBootTime) {
            SystemClock.elapsedRealtimeNanos() / 1000
        } else {
            System.nanoTime() / 1000
        }

    @SuppressLint("MissingPermission")
    private fun startAudio() {
        val sampleRate = AUDIO_SAMPLE_RATE
        val channelCount = 2
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            Log.w(TAG, "audio unavailable, continuing without it")
            return
        }

        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }

        try {
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            val record = AudioRecord(
                MediaRecorder.AudioSource.CAMCORDER,
                sampleRate,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuffer * 4,
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                codec.stop()
                codec.release()
                Log.w(TAG, "AudioRecord did not initialise, continuing without audio")
                return
            }
            record.startRecording()

            audioCodec = codec
            audioRecord = record
            audioThread = Thread({ audioLoop(record, codec, sampleRate, channelCount) }, "kc-audio")
                .also { it.start() }
        } catch (t: Throwable) {
            Log.w(TAG, "audio setup failed, continuing without audio", t)
        }
    }

    /**
     * Timestamp for the next audio chunk, counted in audio frames from an anchor on the camera clock.
     *
     * Counting frames rather than reading the clock per chunk keeps the samples evenly spaced, but it
     * also means the microphone's own clock is what advances the timeline — and it ticks
     * independently of the camera's. Over a session left armed all afternoon the two pull apart, so
     * the anchor is re-taken whenever the gap has grown and no clip is being written. Left alone the
     * drift would first shift the audio of clips saved late in the day and eventually pass
     * [AUDIO_DRIFT_LIMIT_US], where the backstop in [onAudioSampleLocked] discards audio entirely.
     *
     * Re-anchoring only ever moves the timeline forward: timestamps that walk backwards upset both
     * the AAC encoder and the ordering the ring buffer is read back in. When the audio timeline is
     * the one running ahead, this returns null and the chunk is dropped, letting real time catch up
     * with it instead.
     */
    private fun nextAudioPtsUs(frames: Int, sampleRate: Int): Long? = synchronized(lock) {
        val chunkUs = framesToUs(frames, sampleRate)
        val startedAtUs = cameraClockUs() - chunkUs
        if (audioAnchorUs < 0) {
            audioAnchorUs = startedAtUs
            audioFramesQueued = 0L
        }

        val pts = audioAnchorUs + framesToUs(audioFramesQueued, sampleRate)

        if (active == null) {
            val drift = pts - startedAtUs
            if (drift < -AUDIO_RESYNC_SLACK_US) {
                audioAnchorUs = startedAtUs
                audioFramesQueued = frames.toLong()
                return startedAtUs
            }
            if (drift > AUDIO_RESYNC_SLACK_US) {
                // Drop it rather than stamping it no later than the chunk before it.
                return null
            }
        }

        audioFramesQueued += frames
        pts
    }

    private fun audioLoop(record: AudioRecord, codec: MediaCodec, sampleRate: Int, channelCount: Int) {
        val bytesPerFrame = 2 * channelCount
        val chunk = ByteArray(2048 * bytesPerFrame)
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                val read = record.read(chunk, 0, chunk.size)
                // Until the first video sample has revealed which clock the camera uses, there is no
                // correct timestamp to give audio, so it is discarded rather than guessed at.
                val ptsUs = if (read > 0 && cameraClockDetected) {
                    nextAudioPtsUs(read / bytesPerFrame, sampleRate)
                } else {
                    null
                }
                if (ptsUs != null) {
                    val inIndex = codec.dequeueInputBuffer(10_000L)
                    if (inIndex >= 0) {
                        codec.getInputBuffer(inIndex)?.let { buf ->
                            buf.clear()
                            buf.put(chunk, 0, minOf(read, buf.capacity()))
                            codec.queueInputBuffer(inIndex, 0, minOf(read, buf.capacity()), ptsUs, 0)
                        }
                    }
                }

                while (true) {
                    val outIndex = codec.dequeueOutputBuffer(info, 0L)
                    if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        synchronized(lock) { audioFormat = codec.outputFormat }
                        continue
                    }
                    if (outIndex < 0) break
                    val buffer = codec.getOutputBuffer(outIndex)
                    if (buffer != null && info.size > 0 &&
                        info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                    ) {
                        synchronized(lock) { onAudioSampleLocked(buffer, info) }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                }
            }
        } catch (t: Throwable) {
            if (running) Log.w(TAG, "audio loop stopped", t)
        }
    }

    private fun onAudioSampleLocked(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        // Backstop against a mis-stamped audio sample ever reaching a file again. Even if clock
        // detection were somehow wrong, an audio packet that is not plausibly near the video
        // timeline is dropped rather than written, so the container's duration stays honest.
        val newestVideoPtsUs = videoRing.newestPtsUs()
        if (newestVideoPtsUs >= 0 &&
            abs(info.presentationTimeUs - newestVideoPtsUs) > AUDIO_DRIFT_LIMIT_US
        ) {
            if (droppedAudioSamples++ % 200 == 0L) {
                Log.w(
                    TAG,
                    "dropping audio ${(info.presentationTimeUs - newestVideoPtsUs) / 1_000_000}s away " +
                        "from the video timeline",
                )
            }
            return
        }

        audioRing?.add(buffer, info.offset, info.size, info.flags, info.presentationTimeUs)

        val clip = active ?: return
        if (clip.audioTrack < 0) return
        if (info.presentationTimeUs < clip.basePtsUs) return
        if (info.presentationTimeUs > clip.basePtsUs + config.maxClipUs + AUDIO_DRIFT_LIMIT_US) return

        val out = MediaCodec.BufferInfo().apply {
            set(
                info.offset,
                info.size,
                (info.presentationTimeUs - clip.basePtsUs).coerceAtLeast(0L),
                info.flags,
            )
        }
        runCatching {
            clip.muxer.writeSampleData(clip.audioTrack, buffer, out)
            clip.audioSamples++
        }
    }

    private fun framesToUs(frames: Long, sampleRate: Int): Long =
        (frames * 1_000_000.0 / sampleRate).roundToLong()

    private fun framesToUs(frames: Int, sampleRate: Int): Long = framesToUs(frames.toLong(), sampleRate)

    // ------------------------------------------------------------------ sizing

    private fun videoRingCapacity(): Int {
        val seconds = config.preRollUs / 1_000_000.0 + BUFFER_HEADROOM_SEC
        val bytes = (config.bitrateBps / 8.0) * seconds
        return bytes.toLong().coerceIn(MIN_VIDEO_RING_BYTES, MAX_VIDEO_RING_BYTES).toInt()
    }

    private fun videoRingSampleSlots(): Int {
        val seconds = config.preRollUs / 1_000_000.0 + BUFFER_HEADROOM_SEC
        return ceil(seconds * config.fps * 1.3).toInt().coerceAtLeast(256)
    }

    private fun audioRingCapacity(): Int {
        val seconds = config.preRollUs / 1_000_000.0 + BUFFER_HEADROOM_SEC
        return ((160_000 / 8.0) * seconds).toInt().coerceAtLeast(256 * 1024)
    }

    private fun audioRingSampleSlots(): Int {
        val seconds = config.preRollUs / 1_000_000.0 + BUFFER_HEADROOM_SEC
        // AAC-LC packs 1024 samples per frame.
        return ceil(seconds * AUDIO_SAMPLE_RATE / 1024.0 * 1.3).toInt().coerceAtLeast(128)
    }

    companion object {
        private const val AUDIO_SAMPLE_RATE = 48_000

        /**
         * How far an audio packet may sit from the newest video frame before it is treated as
         * mis-stamped. Generous enough for any real encoder/recorder latency, tiny next to the
         * hours-long gap a wrong clock domain produces.
         */
        private const val AUDIO_DRIFT_LIMIT_US = 5_000_000L

        /**
         * Audio/video misalignment tolerated before the anchor is re-taken between clips. Well under
         * [AUDIO_DRIFT_LIMIT_US], so slow clock drift is corrected long before the backstop above
         * would start throwing audio away.
         */
        private const val AUDIO_RESYNC_SLACK_US = 300_000L
        private const val BUFFER_HEADROOM_SEC = 2.0
        private const val MIN_VIDEO_RING_BYTES = 8L * 1024 * 1024
        private const val MAX_VIDEO_RING_BYTES = 384L * 1024 * 1024

        /** RAM the rolling buffer will occupy for the given settings. Shown in the settings screen. */
        fun estimateBufferBytes(bitrateBps: Int, preRollSec: Float): Long {
            val bytes = (bitrateBps / 8.0) * (preRollSec + BUFFER_HEADROOM_SEC)
            return bytes.toLong().coerceIn(MIN_VIDEO_RING_BYTES, MAX_VIDEO_RING_BYTES)
        }
    }
}
