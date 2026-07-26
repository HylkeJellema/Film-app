package com.kickercam.vm

import android.app.Application
import android.util.Size
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kickercam.KickerCamApp
import com.kickercam.camera.CameraDescriptor
import com.kickercam.camera.LensOption
import com.kickercam.capture.CaptureEngine
import com.kickercam.capture.ClipRecorder
import com.kickercam.settings.AppSettings
import com.kickercam.settings.RoiRect
import com.kickercam.storage.Clip
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

class CameraViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as KickerCamApp
    private val store = app.settingsStore
    val repository = app.repository
    val capabilities = app.capabilities

    val engine = CaptureEngine(application, capabilities, repository, viewModelScope)

    val lensOptions: List<LensOption> = capabilities.lensOptions()

    private val _settings = MutableStateFlow(normalise(AppSettings()))
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    val clips: StateFlow<List<Clip>> = repository.clips

    private var loadedFromDisk = false

    init {
        viewModelScope.launch {
            repository.refresh()
        }
        viewModelScope.launch {
            store.settings.collect { stored ->
                // Only the first emission should overwrite in-memory state; later emissions are
                // echoes of our own writes.
                if (!loadedFromDisk) {
                    loadedFromDisk = true
                    apply(normalise(stored), persist = false)
                }
            }
        }
    }

    // ------------------------------------------------------------------ mutations

    fun update(transform: (AppSettings) -> AppSettings) {
        apply(normalise(transform(_settings.value)), persist = true)
    }

    fun selectLens(option: LensOption) {
        update { current ->
            current.copy(cameraId = option.cameraId, zoomRatio = option.zoomRatio)
        }
    }

    fun currentLens(): LensOption? {
        val s = _settings.value
        return lensOptions.firstOrNull {
            it.cameraId == s.cameraId && abs(it.zoomRatio - s.zoomRatio) < 0.05f
        }
    }

    /** Live ROI drag: pushed to the detector immediately, written to disk only when the drag ends. */
    fun dragRoi(roi: RoiRect) {
        val clamped = roi.clampToUnit()
        _settings.value = _settings.value.copy(roi = clamped)
        engine.updateRoi(clamped)
    }

    fun commitRoi() {
        val current = _settings.value
        viewModelScope.launch { store.update(current) }
    }

    fun descriptorForCurrentLens(): CameraDescriptor? =
        capabilities.descriptor(_settings.value.cameraId ?: capabilities.defaultCameraId)

    fun availableSizes(): List<Size> = descriptorForCurrentLens()?.videoSizes ?: emptyList()

    fun availableFps(): List<Int> {
        val descriptor = descriptorForCurrentLens() ?: return listOf(30)
        val s = _settings.value
        return descriptor.fpsOptionsFor(Size(s.widthPx, s.heightPx))
    }

    fun estimatedBufferBytes(): Long =
        ClipRecorder.estimateBufferBytes(_settings.value.bitrateBps, _settings.value.preRollSec)

    private fun apply(next: AppSettings, persist: Boolean) {
        _settings.value = next
        engine.setLensLabel(
            lensOptions.firstOrNull {
                it.cameraId == next.cameraId && abs(it.zoomRatio - next.zoomRatio) < 0.05f
            }?.label ?: (next.cameraId ?: "unknown"),
        )
        engine.updateSettings(next)
        if (persist) viewModelScope.launch { store.update(next) }
    }

    // ------------------------------------------------------------------ clip actions

    fun deleteClip(clip: Clip) {
        viewModelScope.launch { repository.delete(clip) }
    }

    private val _exportMessage = MutableStateFlow<String?>(null)
    val exportMessage: StateFlow<String?> = _exportMessage.asStateFlow()

    fun exportClip(clip: Clip) {
        val again = clip.meta.exported
        viewModelScope.launch {
            repository.exportToMovies(clip)
                .onSuccess {
                    _exportMessage.value = if (again) {
                        "Saved to Movies/KickerCam again"
                    } else {
                        "Saved to Movies/KickerCam"
                    }
                }
                .onFailure { _exportMessage.value = "Export failed: ${it.message}" }
        }
    }

    fun clearExportMessage() {
        _exportMessage.value = null
    }

    fun refreshClips() {
        viewModelScope.launch { repository.refresh() }
    }

    fun deleteAllClips() {
        viewModelScope.launch { repository.deleteAll() }
    }

    // ------------------------------------------------------------------ normalisation

    /** Clamps stored settings to what the currently selected camera can actually deliver. */
    private fun normalise(input: AppSettings): AppSettings {
        val cameraId = input.cameraId?.takeIf { capabilities.descriptor(it) != null }
            ?: capabilities.defaultCameraId
        val descriptor = capabilities.descriptor(cameraId)

        var s = input.copy(cameraId = cameraId)

        if (descriptor != null) {
            val sizes = descriptor.videoSizes
            val requested = Size(s.widthPx, s.heightPx)
            val size = sizes.firstOrNull { it.width == requested.width && it.height == requested.height }
                ?: preferredSize(sizes)
            if (size != null) s = s.copy(widthPx = size.width, heightPx = size.height)

            val fpsChoices = descriptor.fpsOptionsFor(Size(s.widthPx, s.heightPx))
            val fps = fpsChoices.filter { it <= s.fps }.maxOrNull()
                ?: fpsChoices.minOrNull()
                ?: 30
            s = s.copy(fps = fps)

            s = s.copy(zoomRatio = s.zoomRatio.coerceIn(descriptor.zoomRange.lower, descriptor.zoomRange.upper))

            descriptor.isoRange?.let { s = s.copy(iso = s.iso.coerceIn(it.lower, it.upper)) }
            descriptor.exposureTimeRangeNs?.let {
                s = s.copy(shutterNs = s.shutterNs.coerceIn(it.lower, it.upper))
            }
            s = s.copy(focusDiopters = s.focusDiopters.coerceIn(0f, descriptor.minFocusDiopters))
            s = s.copy(
                evCompensationSteps = s.evCompensationSteps
                    .coerceIn(descriptor.evRange.lower, descriptor.evRange.upper),
            )
        }

        val preRoll = s.preRollSec.coerceIn(1f, 15f)
        val postRoll = s.postRollSec.coerceIn(1f, 20f)
        return s.copy(
            bitrateMbps = s.bitrateMbps.coerceIn(4, 200),
            preRollSec = preRoll,
            postRollSec = postRoll,
            maxClipSec = s.maxClipSec.coerceIn(preRoll + postRoll + 1f, 120f),
            sensitivity = s.sensitivity.coerceIn(0f, 1f),
            minConsecutiveHits = s.minConsecutiveHits.coerceIn(1, 8),
            cooldownSec = s.cooldownSec.coerceIn(0f, 30f),
            armDelaySec = s.armDelaySec.coerceIn(0f, 60f),
            roi = s.roi.clampToUnit(),
        )
    }

    /** 1080p is the sweet spot for three concurrent streams, so prefer it when available. */
    private fun preferredSize(sizes: List<Size>): Size? =
        sizes.firstOrNull { it.width == 1920 && it.height == 1080 } ?: sizes.firstOrNull()

    override fun onCleared() {
        engine.stop()
        super.onCleared()
    }
}
