package com.kickercam.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kickercam.settings.AppSettings
import com.kickercam.settings.AwbOption
import com.kickercam.settings.DetectorMode
import com.kickercam.settings.VideoCodecOption
import com.kickercam.ui.theme.KickerOrange
import com.kickercam.vm.CameraViewModel
import kotlin.math.ln
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: CameraViewModel,
    onBack: () -> Unit,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val descriptor = remember(settings.cameraId) { viewModel.descriptorForCurrentLens() }
    val clips by viewModel.clips.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { LensSection(viewModel, settings) }
            item { VideoSection(viewModel, settings) }
            item { ClipTimingSection(viewModel, settings) }
            item { DetectionSection(viewModel, settings) }
            item { ManualControlsSection(viewModel, settings, descriptor) }
            item { StorageSection(viewModel, clipCount = clips.size) }
            item { AboutSection(descriptor) }
        }
    }
}

// ---------------------------------------------------------------------- sections

@Composable
private fun LensSection(viewModel: CameraViewModel, settings: AppSettings) {
    val currentKey = viewModel.currentLens()?.key
    Section("Lens") {
        Text(
            "Which physical lens films. On Samsung hardware the tele module is most reliably " +
                "reached through a “Main @ 5.0x” preset — the phone then switches lenses itself.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Column(Modifier.selectableGroup()) {
            for (option in viewModel.lensOptions) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                ) {
                    RadioButton(
                        selected = option.key == currentKey,
                        onClick = { viewModel.selectLens(option) },
                    )
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(option.label, fontWeight = FontWeight.Medium)
                            if (option.experimental) {
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "experimental",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = KickerOrange,
                                )
                            }
                        }
                        Text(
                            option.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VideoSection(viewModel: CameraViewModel, settings: AppSettings) {
    val sizes = remember(settings.cameraId) { viewModel.availableSizes() }
    val fpsOptions = remember(settings.cameraId, settings.widthPx, settings.heightPx) {
        viewModel.availableFps()
    }

    Section("Video") {
        Label("Resolution")
        ChipRow(
            items = sizes,
            isSelected = { it.width == settings.widthPx && it.height == settings.heightPx },
            label = { "${it.width}x${it.height}" },
            onSelect = { size -> viewModel.update { it.copy(widthPx = size.width, heightPx = size.height) } },
        )

        Spacer(Modifier.height(8.dp))
        Label("Frame rate")
        ChipRow(
            items = fpsOptions,
            isSelected = { it == settings.fps },
            label = { "$it fps" },
            onSelect = { fps -> viewModel.update { it.copy(fps = fps) } },
        )

        Spacer(Modifier.height(8.dp))
        Label("Codec")
        ChipRow(
            items = VideoCodecOption.entries.toList(),
            isSelected = { it == settings.codec },
            label = { it.label },
            onSelect = { codec -> viewModel.update { it.copy(codec = codec) } },
        )
        Text(
            "HEVC halves the file size at 4K but costs a little more encoder headroom.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SliderRow(
            label = "Bitrate",
            value = "${settings.bitrateMbps} Mbps",
            position = settings.bitrateMbps.toFloat(),
            range = 4f..200f,
            steps = 48,
            onChange = { value -> viewModel.update { it.copy(bitrateMbps = value.roundToInt()) } },
        )

        SwitchRow(
            label = "Record audio",
            description = "AAC track muxed alongside the video, buffered the same way.",
            checked = settings.audioEnabled,
            onChange = { checked -> viewModel.update { it.copy(audioEnabled = checked) } },
        )

        Spacer(Modifier.height(8.dp))
        Label("Viewfinder rotation")
        ChipRow(
            items = listOf(null, 0, 90, 180, 270),
            isSelected = { it == settings.rotationOverrideDegrees },
            label = { if (it == null) "Auto" else "$it°" },
            onSelect = { degrees -> viewModel.update { it.copy(rotationOverrideDegrees = degrees) } },
        )
        Text(
            "Auto derives it from the lens mounting and the phone's orientation. If the viewfinder " +
                "comes up on its side, pick the turn that makes it upright — it applies to saved " +
                "clips and the detection box as well. The HUD shows what is being applied.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SwitchRow(
            label = "Stabilisation",
            description = "Optical when the lens has it, electronic otherwise. " +
                "On a fixed tripod you can turn this off for a slightly wider field of view.",
            checked = settings.stabilisation,
            onChange = { checked -> viewModel.update { it.copy(stabilisation = checked) } },
        )
    }
}

@Composable
private fun ClipTimingSection(viewModel: CameraViewModel, settings: AppSettings) {
    val bufferMb = remember(settings.bitrateMbps, settings.preRollSec) {
        viewModel.estimatedBufferBytes() / (1024 * 1024)
    }

    Section("Clip length") {
        SliderRow(
            label = "Before the moment",
            value = "${"%.1f".format(settings.preRollSec)} s",
            position = settings.preRollSec,
            range = 1f..15f,
            steps = 27,
            onChange = { value -> viewModel.update { it.copy(preRollSec = value) } },
        )
        SliderRow(
            label = "After the moment",
            value = "${"%.1f".format(settings.postRollSec)} s",
            position = settings.postRollSec,
            range = 1f..20f,
            steps = 37,
            onChange = { value -> viewModel.update { it.copy(postRollSec = value) } },
        )
        SliderRow(
            label = "Maximum clip length",
            value = "${settings.maxClipSec.roundToInt()} s",
            position = settings.maxClipSec,
            range = (settings.preRollSec + settings.postRollSec + 1f)..120f,
            steps = 0,
            onChange = { value -> viewModel.update { it.copy(maxClipSec = value) } },
        )
        Text(
            "Rolling buffer uses about $bufferMb MB of RAM. Another pass over the kicker while a " +
                "clip is still being written extends that clip instead of starting a new one, up to " +
                "the maximum above.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Key frames are one second apart, so the saved clip can start up to a second earlier " +
                "than requested — never later.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DetectionSection(viewModel: CameraViewModel, settings: AppSettings) {
    Section("Detection") {
        Column(Modifier.selectableGroup()) {
            for (mode in DetectorMode.entries) {
                Row(
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                ) {
                    RadioButton(
                        selected = settings.detectorMode == mode,
                        onClick = { viewModel.update { it.copy(detectorMode = mode) } },
                    )
                    Column(Modifier.weight(1f)) {
                        Text(mode.label, fontWeight = FontWeight.Medium)
                        Text(
                            mode.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        SliderRow(
            label = "Sensitivity",
            value = "${(settings.sensitivity * 100).roundToInt()}%",
            position = settings.sensitivity,
            range = 0f..1f,
            steps = 19,
            onChange = { value -> viewModel.update { it.copy(sensitivity = value) } },
        )
        SliderRow(
            label = "Frames needed to fire",
            value = "${settings.minConsecutiveHits}",
            position = settings.minConsecutiveHits.toFloat(),
            range = 1f..8f,
            steps = 6,
            onChange = { value -> viewModel.update { it.copy(minConsecutiveHits = value.roundToInt()) } },
        )
        SliderRow(
            label = "Cooldown between clips",
            value = "${"%.1f".format(settings.cooldownSec)} s",
            position = settings.cooldownSec,
            range = 0f..30f,
            steps = 59,
            onChange = { value -> viewModel.update { it.copy(cooldownSec = value) } },
        )
        SliderRow(
            label = "Delay after arming",
            value = "${settings.armDelaySec.roundToInt()} s",
            position = settings.armDelaySec,
            range = 0f..60f,
            steps = 11,
            onChange = { value -> viewModel.update { it.copy(armDelaySec = value) } },
        )
        Text(
            "The arming delay gives you time to walk out of frame before the trigger goes live.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SwitchRow(
            label = "Draw what the detector sees",
            description = "Shows the detector's own boxes on the viewfinder. Handy while tuning.",
            checked = settings.showDetectionOverlay,
            onChange = { checked -> viewModel.update { it.copy(showDetectionOverlay = checked) } },
        )
        SwitchRow(
            label = "Dim the screen once armed",
            description = "Blacks out the viewfinder 15 seconds after arming to save battery. " +
                "Tap the screen to bring it back.",
            checked = settings.dimScreenWhenArmed,
            onChange = { checked -> viewModel.update { it.copy(dimScreenWhenArmed = checked) } },
        )
    }
}

@Composable
private fun ManualControlsSection(
    viewModel: CameraViewModel,
    settings: AppSettings,
    descriptor: com.kickercam.camera.CameraDescriptor?,
) {
    Section("Manual controls (experimental)") {
        Text(
            "Off by default because a wrong shutter or ISO ruins every clip of the session, and " +
                "not every lens honours every control. Auto exposure with a fixed frame rate is the " +
                "safe choice; switch this on when you know what you want.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        SwitchRow(
            label = "Enable manual controls",
            description = null,
            checked = settings.manualControlsEnabled,
            onChange = { checked -> viewModel.update { it.copy(manualControlsEnabled = checked) } },
        )

        if (!settings.manualControlsEnabled) {
            SliderRow(
                label = "Exposure compensation",
                value = evLabel(settings, descriptor),
                position = settings.evCompensationSteps.toFloat(),
                range = (descriptor?.evRange?.lower ?: 0).toFloat()..(descriptor?.evRange?.upper ?: 0).toFloat(),
                steps = 0,
                enabled = descriptor != null && descriptor.evRange.upper > descriptor.evRange.lower,
                onChange = { value ->
                    viewModel.update { it.copy(evCompensationSteps = value.roundToInt()) }
                },
            )
            return@Section
        }

        val supportsManualSensor = descriptor?.supportsManualSensor == true
        if (!supportsManualSensor) {
            Text(
                "This lens does not report MANUAL_SENSOR support, so shutter and ISO will be ignored.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        SwitchRow(
            label = "Manual exposure",
            description = "Locks shutter and ISO. For water sports 1/1000s or faster freezes spray.",
            checked = settings.manualExposure,
            enabled = supportsManualSensor,
            onChange = { checked -> viewModel.update { it.copy(manualExposure = checked) } },
        )

        val exposureRange = descriptor?.exposureTimeRangeNs
        if (settings.manualExposure && exposureRange != null) {
            // Shutter is logarithmic — the usable window is clamped to photographic values.
            val minNs = maxOf(exposureRange.lower, 62_500L)
            val maxNs = minOf(exposureRange.upper, 41_666_666L)
            SliderRow(
                label = "Shutter",
                value = settings.shutterLabel,
                position = logPosition(settings.shutterNs.coerceIn(minNs, maxNs), minNs, maxNs),
                range = 0f..1f,
                steps = 0,
                onChange = { position ->
                    viewModel.update { it.copy(shutterNs = logValue(position, minNs, maxNs)) }
                },
            )
        }

        val isoRange = descriptor?.isoRange
        if (settings.manualExposure && isoRange != null) {
            SliderRow(
                label = "ISO",
                value = "${settings.iso}",
                position = logPosition(settings.iso.toLong(), isoRange.lower.toLong(), isoRange.upper.toLong()),
                range = 0f..1f,
                steps = 0,
                onChange = { position ->
                    val iso = logValue(position, isoRange.lower.toLong(), isoRange.upper.toLong()).toInt()
                    viewModel.update { it.copy(iso = iso) }
                },
            )
        }

        val minFocus = descriptor?.minFocusDiopters ?: 0f
        SwitchRow(
            label = "Manual focus",
            description = "Pre-focus on the kicker so autofocus cannot hunt at the wrong moment.",
            checked = settings.manualFocus,
            enabled = minFocus > 0f,
            onChange = { checked -> viewModel.update { it.copy(manualFocus = checked) } },
        )
        if (settings.manualFocus && minFocus > 0f) {
            SliderRow(
                label = "Focus distance",
                value = focusLabel(settings.focusDiopters),
                position = settings.focusDiopters,
                range = 0f..minFocus,
                steps = 0,
                onChange = { value -> viewModel.update { it.copy(focusDiopters = value) } },
            )
        }

        Spacer(Modifier.height(8.dp))
        Label("White balance")
        ChipRow(
            items = AwbOption.entries.toList(),
            isSelected = { it == settings.awb },
            label = { it.label },
            onSelect = { awb -> viewModel.update { it.copy(awb = awb) } },
        )
    }
}

@Composable
private fun StorageSection(viewModel: CameraViewModel, clipCount: Int) {
    val usedMb = remember(clipCount) { viewModel.repository.totalClipBytes() / (1024 * 1024) }
    val freeMb = remember(clipCount) { viewModel.repository.availableBytes() / (1024 * 1024) }

    Section("Storage") {
        Text("$clipCount clips · $usedMb MB used · $freeMb MB free")
        Text(
            "Clips stay in the app's own folder until you export them from the gallery. " +
                "Uninstalling the app deletes anything not exported.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { viewModel.deleteAllClips() }) {
            Text("Delete all clips")
        }
    }
}

@Composable
private fun AboutSection(descriptor: com.kickercam.camera.CameraDescriptor?) {
    Section("This camera") {
        if (descriptor == null) {
            Text("No camera selected.")
            return@Section
        }
        val level = when (descriptor.hardwareLevel) {
            0 -> "LIMITED"
            1 -> "FULL"
            2 -> "LEGACY"
            3 -> "LEVEL_3"
            4 -> "EXTERNAL"
            else -> "unknown"
        }
        Text("Camera id ${descriptor.cameraId} · hardware level $level", style = MaterialTheme.typography.bodySmall)
        Text(
            "Focal lengths " + descriptor.focalLengthsMm.joinToString { "${"%.1f".format(it)}mm" } +
                (descriptor.equivalentFocalMm?.let { " · ${it.roundToInt()}mm equivalent" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "Zoom ${"%.1f".format(descriptor.zoomRange.lower)}x–${"%.1f".format(descriptor.zoomRange.upper)}x · " +
                "manual sensor ${if (descriptor.supportsManualSensor) "yes" else "no"} · " +
                "OIS ${if (descriptor.hasOis) "yes" else "no"}",
            style = MaterialTheme.typography.bodySmall,
        )
        descriptor.isoRange?.let {
            Text("ISO ${it.lower}–${it.upper}", style = MaterialTheme.typography.bodySmall)
        }
        descriptor.exposureTimeRangeNs?.let {
            Text(
                "Shutter ${formatShutter(it.lower)}–${formatShutter(it.upper)}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

// ---------------------------------------------------------------------- building blocks

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = KickerOrange)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun <T> ChipRow(
    items: List<T>,
    isSelected: (T) -> Boolean,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        items(items) { item ->
            FilterChip(
                selected = isSelected(item),
                onClick = { onSelect(item) },
                label = { Text(label(item), style = MaterialTheme.typography.labelSmall) },
            )
        }
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: String,
    position: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
    enabled: Boolean = true,
) {
    Column(Modifier.padding(top = 8.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.bodyMedium, color = KickerOrange)
        }
        Slider(
            value = position.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            enabled = enabled && range.endInclusive > range.start,
        )
    }
}

@Composable
private fun SwitchRow(
    label: String,
    description: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            description?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

// ---------------------------------------------------------------------- helpers

private fun logPosition(value: Long, min: Long, max: Long): Float {
    if (max <= min) return 0f
    val v = value.coerceIn(min, max).toDouble()
    return ((ln(v) - ln(min.toDouble())) / (ln(max.toDouble()) - ln(min.toDouble()))).toFloat()
}

private fun logValue(position: Float, min: Long, max: Long): Long {
    if (max <= min) return min
    val lnMin = ln(min.toDouble())
    val lnMax = ln(max.toDouble())
    return exp(lnMin + (lnMax - lnMin) * position.coerceIn(0f, 1f)).roundToLong().coerceIn(min, max)
}

private fun formatShutter(ns: Long): String {
    val seconds = ns / 1_000_000_000.0
    return if (seconds >= 1.0) "${"%.1f".format(seconds)}s" else "1/${(1.0 / seconds).roundToInt()}s"
}

private fun focusLabel(diopters: Float): String =
    if (diopters <= 0.01f) "∞" else "${"%.2f".format(1f / diopters)} m"

private fun evLabel(settings: AppSettings, descriptor: com.kickercam.camera.CameraDescriptor?): String {
    val step = descriptor?.evStep ?: (1.0 / 3.0)
    val ev = settings.evCompensationSteps * step
    return "${if (ev >= 0) "+" else ""}${"%.1f".format(ev)} EV"
}
