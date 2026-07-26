package com.kickercam.camera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import com.kickercam.settings.AppSettings
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

private const val TAG = "Camera2Session"

/** Everything needed to bring a session up. */
data class SessionRequest(
    val cameraId: String,
    val physicalCameraId: String?,
    val previewSurface: Surface,
    val recordSurface: Surface,
    val analysisSurface: Surface?,
    val fps: Int,
    val settings: AppSettings,
)

/** Live values read back from the sensor, shown on the HUD. */
data class SensorReadout(
    val isoActual: Int? = null,
    val exposureTimeNs: Long? = null,
    val focusDistanceDiopters: Float? = null,
    val zoomRatio: Float? = null,
    val afLocked: Boolean = false,
    val aeLocked: Boolean = false,
)

/**
 * Thin, explicit wrapper around Camera2.
 *
 * Camera2 rather than CameraX because this app needs two things CameraX will not give up: a raw
 * encoder input surface to drive the rolling buffer, and unrestricted manual sensor control plus
 * direct physical-lens addressing.
 */
class Camera2Session(
    context: Context,
    private val capabilities: CameraCapabilities,
    private val listener: Listener,
) {

    interface Listener {
        fun onSessionReady(descriptor: CameraDescriptor)
        fun onSessionFailed(message: String, recoverable: Boolean)
        fun onDisconnected()
        fun onReadout(readout: SensorReadout)
    }

    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private val thread = HandlerThread("kc-camera").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var request: SessionRequest? = null
    private var descriptor: CameraDescriptor? = null
    private var lastReadoutAtMs = 0L

    @SuppressLint("MissingPermission")
    fun open(request: SessionRequest) {
        handler.post {
            closeInternal()
            this.request = request
            val target = capabilities.descriptor(request.cameraId)
            if (target == null) {
                listener.onSessionFailed("Camera ${request.cameraId} is not available", false)
                return@post
            }
            descriptor = target

            try {
                manager.openCamera(request.cameraId, deviceCallback, handler)
            } catch (e: CameraAccessException) {
                listener.onSessionFailed("Could not open camera: ${e.message}", true)
            } catch (e: SecurityException) {
                listener.onSessionFailed("Camera permission denied", false)
            }
        }
    }

    fun close() {
        handler.post { closeInternal() }
    }

    /**
     * Closes the camera and waits for it to actually be closed.
     *
     * This blocks on purpose. The caller releases the encoder's input surface immediately afterwards,
     * and a camera still holding that surface as a target when it disappears takes the process down
     * in native code. Call it off the main thread.
     */
    fun release(timeoutMs: Long = 2_000L) {
        val closed = CountDownLatch(1)
        val posted = handler.post {
            closeInternal()
            closed.countDown()
        }
        if (posted) {
            runCatching { closed.await(timeoutMs, TimeUnit.MILLISECONDS) }
                .onFailure { Log.w(TAG, "interrupted while closing camera", it) }
        }
        thread.quitSafely()
    }

    /** Re-issues the repeating request so control changes take effect without rebuilding the session. */
    fun applyControls(settings: AppSettings) {
        handler.post {
            val req = request?.copy(settings = settings) ?: return@post
            request = req
            startRepeating(req)
        }
    }

    /** Nudges autofocus to lock on whatever is in the box; useful before walking away from the tripod. */
    fun triggerAutoFocus() {
        handler.post {
            val req = request ?: return@post
            val activeSession = session ?: return@post
            val device = device ?: return@post
            runCatching {
                val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                addTargets(builder, req)
                applySettingsTo(builder, req)
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
                activeSession.capture(builder.build(), null, handler)
            }
        }
    }

    private fun closeInternal() {
        runCatching { session?.stopRepeating() }
        runCatching { session?.close() }
        session = null
        runCatching { device?.close() }
        device = null
    }

    private val deviceCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            device = camera
            val req = request
            if (req == null) {
                camera.close()
                return
            }
            configureSession(camera, req)
        }

        override fun onDisconnected(camera: CameraDevice) {
            Log.w(TAG, "camera disconnected")
            closeInternal()
            listener.onDisconnected()
        }

        override fun onError(camera: CameraDevice, error: Int) {
            val reason = when (error) {
                ERROR_CAMERA_IN_USE -> "Camera already in use by another app"
                ERROR_MAX_CAMERAS_IN_USE -> "Too many cameras open"
                ERROR_CAMERA_DISABLED -> "Camera disabled by policy"
                ERROR_CAMERA_DEVICE -> "Camera device error"
                ERROR_CAMERA_SERVICE -> "Camera service error"
                else -> "Camera error $error"
            }
            closeInternal()
            listener.onSessionFailed(reason, error != ERROR_CAMERA_DISABLED)
        }
    }

    private fun configureSession(camera: CameraDevice, req: SessionRequest) {
        val surfaces = listOfNotNull(req.previewSurface, req.recordSurface, req.analysisSurface)
        val outputs = surfaces.map { surface ->
            OutputConfiguration(surface).apply {
                if (req.physicalCameraId != null) {
                    runCatching { setPhysicalCameraId(req.physicalCameraId) }
                        .onFailure { Log.w(TAG, "physical camera id rejected", it) }
                }
            }
        }

        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(configured: CameraCaptureSession) {
                session = configured
                startRepeating(req)
                descriptor?.let { listener.onSessionReady(it) }
            }

            override fun onConfigureFailed(configured: CameraCaptureSession) {
                Log.e(TAG, "session configuration failed")
                runCatching { configured.close() }
                session = null
                listener.onSessionFailed(
                    "This camera cannot run ${surfaces.size} streams at " +
                        "${req.settings.widthPx}x${req.settings.heightPx}",
                    true,
                )
            }
        }

        try {
            camera.createCaptureSession(
                SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, executor, callback),
            )
        } catch (e: Throwable) {
            listener.onSessionFailed("Could not create capture session: ${e.message}", true)
        }
    }

    private fun addTargets(builder: CaptureRequest.Builder, req: SessionRequest) {
        builder.addTarget(req.previewSurface)
        builder.addTarget(req.recordSurface)
        req.analysisSurface?.let { builder.addTarget(it) }
    }

    private fun startRepeating(req: SessionRequest) {
        val activeSession = session ?: return
        val camera = device ?: return
        try {
            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            addTargets(builder, req)
            applySettingsTo(builder, req)
            activeSession.setRepeatingRequest(builder.build(), captureCallback, handler)
        } catch (t: Throwable) {
            Log.e(TAG, "setRepeatingRequest failed", t)
            listener.onSessionFailed("Could not start preview: ${t.message}", true)
        }
    }

    private fun applySettingsTo(builder: CaptureRequest.Builder, req: SessionRequest) {
        val cam = descriptor ?: return
        val s = req.settings

        builder.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CameraMetadata.CONTROL_CAPTURE_INTENT_VIDEO_RECORD)
        builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, cam.bestFpsRange(req.fps))

        // ---- Stabilisation: prefer optical, fall back to electronic. ----
        if (s.stabilisation) {
            if (cam.hasOis) {
                builder.set(
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                    CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON,
                )
            } else if (cam.hasEis) {
                builder.set(
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON,
                )
            }
        } else {
            if (cam.hasOis) {
                builder.set(
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                    CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF,
                )
            }
            if (cam.hasEis) {
                builder.set(
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
                )
            }
        }

        // ---- Zoom. This is what lands us on the tele module on Samsung hardware. ----
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && cam.supportsZoomRatio) {
            val clamped = s.zoomRatio.coerceIn(cam.zoomRange.lower, cam.zoomRange.upper)
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, clamped)
        }

        builder.set(CaptureRequest.CONTROL_AWB_MODE, s.awb.mode)

        val manual = s.manualControlsEnabled

        // ---- Exposure ----
        if (manual && s.manualExposure && cam.supportsManualSensor) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            cam.exposureTimeRangeNs?.let { range ->
                builder.set(
                    CaptureRequest.SENSOR_EXPOSURE_TIME,
                    s.shutterNs.coerceIn(range.lower, range.upper),
                )
            }
            cam.isoRange?.let { range ->
                builder.set(CaptureRequest.SENSOR_SENSITIVITY, s.iso.coerceIn(range.lower, range.upper))
            }
            // The frame duration has to leave room for the requested frame rate.
            builder.set(CaptureRequest.SENSOR_FRAME_DURATION, 1_000_000_000L / req.fps)
        } else {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            builder.set(
                CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                s.evCompensationSteps.coerceIn(cam.evRange.lower, cam.evRange.upper),
            )
            // Never let auto-exposure drop below the requested frame rate to gather light —
            // motion blur ruins action frames and slow shutter breaks the detector.
            builder.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_AUTO)
        }

        // ---- Focus ----
        if (manual && s.manualFocus && cam.minFocusDiopters > 0f) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            builder.set(
                CaptureRequest.LENS_FOCUS_DISTANCE,
                s.focusDiopters.coerceIn(0f, cam.minFocusDiopters),
            )
        } else {
            builder.set(
                CaptureRequest.CONTROL_AF_MODE,
                CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
            )
        }
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastReadoutAtMs < 250L) return
            lastReadoutAtMs = now

            listener.onReadout(
                SensorReadout(
                    isoActual = result.get(CaptureResult.SENSOR_SENSITIVITY),
                    exposureTimeNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                    focusDistanceDiopters = result.get(CaptureResult.LENS_FOCUS_DISTANCE),
                    zoomRatio = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        result.get(CaptureResult.CONTROL_ZOOM_RATIO)
                    } else {
                        null
                    },
                    afLocked = result.get(CaptureResult.CONTROL_AF_STATE) ==
                        CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED,
                    aeLocked = result.get(CaptureResult.CONTROL_AE_STATE) ==
                        CaptureResult.CONTROL_AE_STATE_CONVERGED,
                ),
            )
        }
    }

    companion object {
        /**
         * Picks the analysis resolution. Small on purpose: detection accuracy at these distances is
         * driven by the box, not by pixels, and a small stream keeps the three-stream combination
         * within what every device supports.
         */
        fun analysisSizeFor(recordSize: Size): Size {
            val targetWidth = 640
            val aspect = recordSize.width.toFloat() / recordSize.height.toFloat()
            val height = (targetWidth / aspect).toInt().let { it - (it % 2) }
            return Size(targetWidth, height.coerceAtLeast(360))
        }

        /**
         * Rotation from analysis/sensor space to what the user sees, given the sensor mounting and the
         * current display rotation.
         */
        /**
         * The rotation to apply, honouring a manual override when one is set.
         *
         * The automatic path can only be as good as `SENSOR_ORIENTATION` and the orientation the
         * accelerometer reports, and neither is reliable on every device — an override is the only
         * way for someone holding the phone to settle it.
         */
        fun effectiveRotationDegrees(
            overrideDegrees: Int?,
            sensorOrientation: Int,
            displayRotationDegrees: Int,
            facing: Int,
        ): Int = overrideDegrees?.let { ((it % 360) + 360) % 360 }
            ?: displayRotationDegrees(sensorOrientation, displayRotationDegrees, facing)

        fun displayRotationDegrees(sensorOrientation: Int, displayRotationDegrees: Int, facing: Int): Int {
            val normalised = if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                (sensorOrientation + displayRotationDegrees) % 360
            } else {
                (sensorOrientation - displayRotationDegrees + 360) % 360
            }
            return normalised
        }
    }
}
