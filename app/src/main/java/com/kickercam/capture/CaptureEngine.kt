package com.kickercam.capture

import android.content.Context
import android.util.Log
import android.util.Size
import android.view.Surface
import com.kickercam.camera.Camera2Session
import com.kickercam.camera.CameraCapabilities
import com.kickercam.camera.CameraDescriptor
import com.kickercam.camera.SensorReadout
import com.kickercam.camera.SessionRequest
import com.kickercam.detect.DetectionOutcome
import com.kickercam.detect.DetectionPipeline
import com.kickercam.detect.TriggerController
import com.kickercam.settings.AppSettings
import com.kickercam.settings.RoiRect
import com.kickercam.storage.ClipMeta
import com.kickercam.storage.ClipRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "CaptureEngine"

enum class CaptureLifecycle { IDLE, STARTING, RUNNING, ERROR }

data class CaptureStatus(
    val lifecycle: CaptureLifecycle = CaptureLifecycle.IDLE,
    val armed: Boolean = false,
    val armCountdownMs: Long = 0L,
    val cooldownMs: Long = 0L,
    val savingClip: Boolean = false,
    val currentClipMs: Long = 0L,
    val bufferedSec: Float = 0f,
    val bufferFill: Float = 0f,
    val effectiveSize: Size = Size(1920, 1080),
    val effectiveFps: Int = 60,
    val detectionAvailable: Boolean = true,
    val readout: SensorReadout = SensorReadout(),
    val message: String? = null,
    val error: String? = null,
    val clipsSavedThisSession: Int = 0,
    val bufferBytes: Long = 0L,
)

/**
 * Orchestrates the camera session, the rolling-buffer recorder and the detector.
 *
 * The whole point of this class is that the camera never stops rolling. Detection only decides which
 * few seconds of an always-running encode get written to disk, which is why the moment before the
 * trigger can be saved at all.
 */
class CaptureEngine(
    private val context: Context,
    private val capabilities: CameraCapabilities,
    private val repository: ClipRepository,
    private val scope: CoroutineScope,
) {

    private val _status = MutableStateFlow(CaptureStatus())
    val status: StateFlow<CaptureStatus> = _status.asStateFlow()

    /** Kept separate from [status] because it updates at frame rate. */
    private val _detection = MutableStateFlow(DetectionOutcome())
    val detection: StateFlow<DetectionOutcome> = _detection.asStateFlow()

    private val triggerController = TriggerController()

    private var session: Camera2Session? = null
    private var recorder: ClipRecorder? = null
    private var pipeline: DetectionPipeline? = null

    private var previewTarget: PreviewTarget? = null
    private var settings: AppSettings = AppSettings()
    private var activeDescriptor: CameraDescriptor? = null
    private var lensLabel: String = ""

    private var fallbackAttempt = 0
    private var statusTicker: Job? = null
    private var restarting = false

    // ------------------------------------------------------------------ public API

    fun attachPreview(target: PreviewTarget) {
        previewTarget = target
        scope.launch { bringUp() }
    }

    /**
     * Claims the IDLE -> STARTING transition on the caller's thread so two rapid attaches cannot both
     * open the camera, then does the slow part (encoder and camera creation, tens of milliseconds)
     * off the main thread.
     */
    private suspend fun bringUp() {
        if (_status.value.lifecycle != CaptureLifecycle.IDLE) return
        _status.value = _status.value.copy(lifecycle = CaptureLifecycle.STARTING, error = null)
        withContext(Dispatchers.Default) { startSession() }
    }

    fun detachPreview() {
        previewTarget = null
        scope.launch { stopAsync() }
    }

    fun setLensLabel(label: String) {
        lensLabel = label
    }

    /**
     * Applies new settings. Format-level changes rebuild the session; everything else is pushed into
     * the running session so the buffer is not thrown away.
     */
    fun updateSettings(next: AppSettings) {
        val previous = settings
        settings = next

        triggerController.minConsecutiveHits = next.minConsecutiveHits
        triggerController.cooldownMs = (next.cooldownSec * 1000).toLong()
        triggerController.armDelayMs = (next.armDelaySec * 1000).toLong()

        pipeline?.configure(
            mode = next.detectorMode,
            sensitivity = next.sensitivity,
            roi = next.roi,
        )

        if (_status.value.lifecycle != CaptureLifecycle.RUNNING) return

        if (requiresRebuild(previous, next)) {
            fallbackAttempt = 0
            restart("format changed")
        } else {
            session?.applyControls(next)
        }
    }

    fun setArmed(armed: Boolean) {
        if (armed) triggerController.arm() else triggerController.disarm()
        pipeline?.setEnabled(true)
        _status.value = _status.value.copy(armed = armed)
    }

    /** Saves the last few seconds right now, regardless of what the detector thinks. */
    fun manualTrigger() {
        recorder?.trigger("manual")
    }

    fun focusNow() {
        session?.triggerAutoFocus()
    }

    fun updateRoi(roi: RoiRect) {
        settings = settings.copy(roi = roi)
        pipeline?.configure(
            mode = settings.detectorMode,
            sensitivity = settings.sensitivity,
            roi = roi,
        )
    }

    /** Teardown joins encoder threads, so keep it off the main thread wherever possible. */
    suspend fun stopAsync() = withContext(Dispatchers.IO) { stop() }

    fun stop() {
        statusTicker?.cancel()
        statusTicker = null
        triggerController.disarm()

        session?.release()
        session = null
        pipeline?.release()
        pipeline = null
        recorder?.stop()
        recorder = null

        _status.value = _status.value.copy(
            lifecycle = CaptureLifecycle.IDLE,
            armed = false,
            savingClip = false,
        )
    }

    fun clearMessage() {
        _status.value = _status.value.copy(message = null, error = null)
    }

    // ------------------------------------------------------------------ session bring-up

    private fun requiresRebuild(a: AppSettings, b: AppSettings): Boolean =
        a.cameraId != b.cameraId ||
            a.widthPx != b.widthPx ||
            a.heightPx != b.heightPx ||
            a.fps != b.fps ||
            a.bitrateMbps != b.bitrateMbps ||
            a.codec != b.codec ||
            a.audioEnabled != b.audioEnabled ||
            a.preRollSec != b.preRollSec ||
            a.maxClipSec != b.maxClipSec

    private fun restart(reason: String) {
        if (restarting) return
        restarting = true
        scope.launch {
            Log.i(TAG, "restarting session: $reason")
            val wasArmed = _status.value.armed
            stopAsync()
            delay(120)
            restarting = false
            bringUp()
            if (wasArmed) setArmed(true)
        }
    }

    private fun startSession() {
        // The viewfinder went away between the attach and here; drop back to IDLE so the next
        // attach is not blocked by a STARTING claim nobody will ever clear.
        val target = previewTarget
        if (target == null) {
            _status.value = _status.value.copy(lifecycle = CaptureLifecycle.IDLE)
            return
        }

        val cameraId = settings.cameraId ?: capabilities.defaultCameraId
        if (cameraId == null) {
            _status.value = _status.value.copy(
                lifecycle = CaptureLifecycle.ERROR,
                error = "No camera found on this device",
            )
            return
        }
        val descriptor = capabilities.descriptor(cameraId)
        if (descriptor == null) {
            _status.value = _status.value.copy(
                lifecycle = CaptureLifecycle.ERROR,
                error = "Camera $cameraId is not usable",
            )
            return
        }

        val plan = buildPlan(descriptor)
        // Must happen before the session is configured: this is what fixes the preview stream size.
        target.setBufferSize(plan.size)

        val recorderConfig = RecorderConfig(
            widthPx = plan.size.width,
            heightPx = plan.size.height,
            fps = plan.fps,
            bitrateBps = settings.bitrateBps,
            mime = settings.codec.mime,
            preRollUs = settings.preRollUs,
            postRollUs = settings.postRollUs,
            maxClipUs = settings.maxClipUs,
            audioEnabled = settings.audioEnabled,
            outputDir = repository.clipsDir,
            cameraTimestampIsRealtime = descriptor.timestampIsRealtime,
        )

        val newRecorder = ClipRecorder(recorderConfig, recorderListener)
        val encoderSurface = try {
            newRecorder.start()
        } catch (t: Throwable) {
            Log.e(TAG, "encoder failed to start", t)
            _status.value = _status.value.copy(
                lifecycle = CaptureLifecycle.ERROR,
                error = "Encoder rejected ${plan.size.width}x${plan.size.height}@${plan.fps}: ${t.message}",
            )
            return
        }
        recorder = newRecorder

        val newPipeline = if (plan.useAnalysisStream) {
            DetectionPipeline(
                analysisSize = Camera2Session.analysisSizeFor(plan.size),
                onVerdict = ::onVerdict,
            ).also {
                it.configure(settings.detectorMode, settings.sensitivity, settings.roi)
            }
        } else {
            null
        }
        pipeline = newPipeline

        val newSession = Camera2Session(context, capabilities, sessionListener)
        session = newSession
        newSession.open(
            SessionRequest(
                cameraId = cameraId,
                previewSurface = target.surface,
                recordSurface = encoderSurface,
                analysisSurface = newPipeline?.surface,
                fps = plan.fps,
                settings = settings,
            ),
        )

        _status.value = _status.value.copy(
            effectiveSize = plan.size,
            effectiveFps = plan.fps,
            detectionAvailable = plan.useAnalysisStream,
            message = plan.note,
            bufferBytes = newRecorder.estimatedVideoBufferBytes.toLong(),
        )

        startStatusTicker()
    }

    private data class Plan(
        val size: Size,
        val fps: Int,
        val useAnalysisStream: Boolean,
        val note: String?,
    )

    /**
     * Chooses the format actually used, applying the fallback ladder when a previous attempt failed
     * to configure. The user's request is honoured first; each fallback gives up as little as possible.
     */
    private fun buildPlan(descriptor: CameraDescriptor): Plan {
        val requested = Size(settings.widthPx, settings.heightPx)
        val supported = descriptor.videoSizes
        val size = supported.firstOrNull { it.width == requested.width && it.height == requested.height }
            ?: supported.firstOrNull() ?: requested
        val fps = settings.fps.coerceAtMost(descriptor.maxFps(size))

        return when (fallbackAttempt) {
            0 -> Plan(size, fps, useAnalysisStream = true, note = null)

            1 -> {
                // Three simultaneous streams at the top resolution is the usual sticking point.
                val aspect = size.width.toFloat() / size.height.toFloat()
                val smaller = supported.firstOrNull {
                    it.width <= 1920 &&
                        kotlin.math.abs(it.width.toFloat() / it.height.toFloat() - aspect) < 0.05f
                } ?: size
                Plan(
                    smaller,
                    settings.fps.coerceAtMost(descriptor.maxFps(smaller)),
                    useAnalysisStream = true,
                    note = "Dropped to ${smaller.width}x${smaller.height} — this lens cannot run " +
                        "preview, recording and detection together at ${size.width}x${size.height}.",
                )
            }

            else -> Plan(
                size,
                fps,
                useAnalysisStream = false,
                note = "Automatic detection is off: this lens cannot run a third stream. " +
                    "Use the manual capture button.",
            )
        }
    }

    private fun startStatusTicker() {
        statusTicker?.cancel()
        statusTicker = scope.launch {
            while (true) {
                val stats = recorder?.stats() ?: RecorderStats()
                _status.value = _status.value.copy(
                    bufferedSec = stats.bufferedSec,
                    bufferFill = stats.bufferFillFraction,
                    savingClip = stats.isSavingClip,
                    currentClipMs = stats.currentClipElapsedMs,
                    armCountdownMs = triggerController.armDelayRemainingMs(),
                    cooldownMs = triggerController.cooldownRemainingMs(),
                )
                delay(200)
            }
        }
    }

    // ------------------------------------------------------------------ callbacks

    private fun onVerdict(outcome: DetectionOutcome, frameTimestampNs: Long) {
        _detection.value = outcome
        if (triggerController.submit(outcome.hit)) {
            recorder?.trigger(
                reason = outcome.label ?: settings.detectorMode.label,
                momentPtsUs = frameTimestampNs / 1000L,
            )
        }
    }

    private val sessionListener = object : Camera2Session.Listener {
        override fun onSessionReady(descriptor: CameraDescriptor) {
            activeDescriptor = descriptor
            _status.value = _status.value.copy(lifecycle = CaptureLifecycle.RUNNING, error = null)
        }

        override fun onSessionFailed(message: String, recoverable: Boolean) {
            Log.w(TAG, "session failed: $message (recoverable=$recoverable)")
            if (recoverable && fallbackAttempt < 2) {
                fallbackAttempt++
                restart("fallback $fallbackAttempt after: $message")
            } else {
                _status.value = _status.value.copy(
                    lifecycle = CaptureLifecycle.ERROR,
                    error = message,
                )
            }
        }

        override fun onDisconnected() {
            _status.value = _status.value.copy(
                lifecycle = CaptureLifecycle.ERROR,
                error = "Camera was taken by another app",
            )
        }

        override fun onReadout(readout: SensorReadout) {
            _status.value = _status.value.copy(readout = readout)
        }
    }

    private val recorderListener = object : ClipRecorder.Listener {
        override fun onClipStarted(reason: String) {
            _status.value = _status.value.copy(savingClip = true)
        }

        override fun onClipFinished(result: ClipResult) {
            _status.value = _status.value.copy(
                savingClip = false,
                clipsSavedThisSession = _status.value.clipsSavedThisSession + 1,
            )
            scope.launch { registerClip(result) }
        }

        override fun onRecorderError(message: String, cause: Throwable?) {
            Log.w(TAG, "recorder: $message", cause)
            _status.value = _status.value.copy(error = message)
        }
    }

    private suspend fun registerClip(result: ClipResult) {
        val id = result.file.nameWithoutExtension
        repository.register(
            ClipMeta(
                id = id,
                fileName = result.file.name,
                createdAtEpochMs = System.currentTimeMillis(),
                durationMs = result.durationMs,
                momentOffsetMs = result.momentOffsetMs,
                widthPx = result.widthPx,
                heightPx = result.heightPx,
                fps = result.fps,
                triggerReason = result.triggerReason,
                detectorMode = settings.detectorMode.label,
                lensLabel = lensLabel,
                hasAudio = result.hasAudio,
            ),
        )
    }

    val clipsDir: File get() = repository.clipsDir
}
