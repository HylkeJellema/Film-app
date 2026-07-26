package com.kickercam.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("kickercam_settings")

/** Persists [AppSettings]. Every field is stored individually so adding fields stays backwards compatible. */
class SettingsStore(private val context: Context) {

    private object Keys {
        val cameraId = stringPreferencesKey("camera_id")
        val zoomRatio = floatPreferencesKey("zoom_ratio")
        val fps = intPreferencesKey("fps")
        val bitrateMbps = intPreferencesKey("bitrate_mbps")
        val codec = stringPreferencesKey("codec")
        val audioEnabled = booleanPreferencesKey("audio_enabled")
        val stabilisation = booleanPreferencesKey("stabilisation")
        val preRollSec = floatPreferencesKey("pre_roll_sec")
        val postRollSec = floatPreferencesKey("post_roll_sec")
        val maxClipSec = floatPreferencesKey("max_clip_sec")
        val detectorMode = stringPreferencesKey("detector_mode")
        val sensitivity = floatPreferencesKey("sensitivity")
        val minConsecutiveHits = intPreferencesKey("min_consecutive_hits")
        val cooldownSec = floatPreferencesKey("cooldown_sec")
        val armDelaySec = floatPreferencesKey("arm_delay_sec")
        val roiLeft = floatPreferencesKey("roi_left")
        val roiTop = floatPreferencesKey("roi_top")
        val roiWidth = floatPreferencesKey("roi_width")
        val roiHeight = floatPreferencesKey("roi_height")
        val showDetectionOverlay = booleanPreferencesKey("show_detection_overlay")
        val manualControlsEnabled = booleanPreferencesKey("manual_controls_enabled")
        val manualExposure = booleanPreferencesKey("manual_exposure")
        val shutterNs = longPreferencesKey("shutter_ns")
        val iso = intPreferencesKey("iso")
        val manualFocus = booleanPreferencesKey("manual_focus")
        val focusDiopters = floatPreferencesKey("focus_diopters")
        val evCompensationSteps = intPreferencesKey("ev_compensation_steps")
        val awb = stringPreferencesKey("awb")
        val dimScreenWhenArmed = booleanPreferencesKey("dim_screen_when_armed")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        val d = AppSettings()
        AppSettings(
            cameraId = p[Keys.cameraId],
            zoomRatio = p[Keys.zoomRatio] ?: d.zoomRatio,
            fps = p[Keys.fps] ?: d.fps,
            bitrateMbps = p[Keys.bitrateMbps] ?: d.bitrateMbps,
            codec = p[Keys.codec]?.let { runCatching { VideoCodecOption.valueOf(it) }.getOrNull() } ?: d.codec,
            audioEnabled = p[Keys.audioEnabled] ?: d.audioEnabled,
            stabilisation = p[Keys.stabilisation] ?: d.stabilisation,
            preRollSec = p[Keys.preRollSec] ?: d.preRollSec,
            postRollSec = p[Keys.postRollSec] ?: d.postRollSec,
            maxClipSec = p[Keys.maxClipSec] ?: d.maxClipSec,
            detectorMode = p[Keys.detectorMode]?.let { runCatching { DetectorMode.valueOf(it) }.getOrNull() } ?: d.detectorMode,
            sensitivity = p[Keys.sensitivity] ?: d.sensitivity,
            minConsecutiveHits = p[Keys.minConsecutiveHits] ?: d.minConsecutiveHits,
            cooldownSec = p[Keys.cooldownSec] ?: d.cooldownSec,
            armDelaySec = p[Keys.armDelaySec] ?: d.armDelaySec,
            roi = RoiRect(
                left = p[Keys.roiLeft] ?: d.roi.left,
                top = p[Keys.roiTop] ?: d.roi.top,
                width = p[Keys.roiWidth] ?: d.roi.width,
                height = p[Keys.roiHeight] ?: d.roi.height,
            ).clampToUnit(),
            showDetectionOverlay = p[Keys.showDetectionOverlay] ?: d.showDetectionOverlay,
            manualControlsEnabled = p[Keys.manualControlsEnabled] ?: d.manualControlsEnabled,
            manualExposure = p[Keys.manualExposure] ?: d.manualExposure,
            shutterNs = p[Keys.shutterNs] ?: d.shutterNs,
            iso = p[Keys.iso] ?: d.iso,
            manualFocus = p[Keys.manualFocus] ?: d.manualFocus,
            focusDiopters = p[Keys.focusDiopters] ?: d.focusDiopters,
            evCompensationSteps = p[Keys.evCompensationSteps] ?: d.evCompensationSteps,
            awb = p[Keys.awb]?.let { runCatching { AwbOption.valueOf(it) }.getOrNull() } ?: d.awb,
            dimScreenWhenArmed = p[Keys.dimScreenWhenArmed] ?: d.dimScreenWhenArmed,
        )
    }

    suspend fun update(s: AppSettings) {
        context.dataStore.edit { p ->
            s.cameraId?.let { p[Keys.cameraId] = it } ?: p.remove(Keys.cameraId)
            p[Keys.zoomRatio] = s.zoomRatio
            p[Keys.fps] = s.fps
            p[Keys.bitrateMbps] = s.bitrateMbps
            p[Keys.codec] = s.codec.name
            p[Keys.audioEnabled] = s.audioEnabled
            p[Keys.stabilisation] = s.stabilisation
            p[Keys.preRollSec] = s.preRollSec
            p[Keys.postRollSec] = s.postRollSec
            p[Keys.maxClipSec] = s.maxClipSec
            p[Keys.detectorMode] = s.detectorMode.name
            p[Keys.sensitivity] = s.sensitivity
            p[Keys.minConsecutiveHits] = s.minConsecutiveHits
            p[Keys.cooldownSec] = s.cooldownSec
            p[Keys.armDelaySec] = s.armDelaySec
            p[Keys.roiLeft] = s.roi.left
            p[Keys.roiTop] = s.roi.top
            p[Keys.roiWidth] = s.roi.width
            p[Keys.roiHeight] = s.roi.height
            p[Keys.showDetectionOverlay] = s.showDetectionOverlay
            p[Keys.manualControlsEnabled] = s.manualControlsEnabled
            p[Keys.manualExposure] = s.manualExposure
            p[Keys.shutterNs] = s.shutterNs
            p[Keys.iso] = s.iso
            p[Keys.manualFocus] = s.manualFocus
            p[Keys.focusDiopters] = s.focusDiopters
            p[Keys.evCompensationSteps] = s.evCompensationSteps
            p[Keys.awb] = s.awb.name
            p[Keys.dimScreenWhenArmed] = s.dimScreenWhenArmed
        }
    }
}
