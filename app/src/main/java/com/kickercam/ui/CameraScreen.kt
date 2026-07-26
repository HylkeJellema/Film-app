package com.kickercam.ui

import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessLow
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kickercam.capture.CaptureLifecycle
import com.kickercam.settings.AppSettings
import com.kickercam.ui.theme.KickerGreen
import com.kickercam.ui.theme.KickerOrange
import com.kickercam.ui.theme.KickerRed
import com.kickercam.vm.CameraViewModel
import kotlinx.coroutines.delay

@Composable
fun CameraScreen(
    viewModel: CameraViewModel,
    onOpenSettings: () -> Unit,
    onOpenGallery: () -> Unit,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val status by viewModel.engine.status.collectAsStateWithLifecycle()
    val detection by viewModel.engine.detection.collectAsStateWithLifecycle()

    val context = LocalContext.current
    var dimmed by remember { mutableStateOf(false) }

    // Sensor-driven, not display-driven: the activity is locked to landscape, so the display keeps
    // reporting its natural orientation and would push a bogus quarter turn into the pipeline.
    val deviceRotation = rememberDeviceRotationDegrees()
    LaunchedEffect(deviceRotation) {
        viewModel.engine.setDisplayRotation(deviceRotation)
    }

    // Keep the viewfinder alive: the phone is on a tripod and nobody is going to tap it.
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            restoreBrightness(context)
        }
    }

    // Auto-dim once armed so a long session does not cook the battery.
    LaunchedEffect(status.armed, settings.dimScreenWhenArmed) {
        if (status.armed && settings.dimScreenWhenArmed) {
            delay(15_000)
            dimmed = true
        } else {
            dimmed = false
        }
    }

    LaunchedEffect(dimmed) {
        if (dimmed) setBrightness(context, 0.02f) else restoreBrightness(context)
    }

    val aspect = remember(status.effectiveSize, status.previewRotation) {
        previewAspectRatio(status.effectiveSize, status.previewRotation)
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {

        // No fillMaxSize here: it fixes the constraints, which makes aspectRatio a no-op and lets the
        // preview stretch to the whole screen. aspectRatio alone fits-and-letterboxes correctly.
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .aspectRatio(aspect),
        ) {
            CameraPreview(
                bufferSize = status.effectiveSize,
                rotationDegrees = status.previewRotation,
                onTargetChanged = { target ->
                    if (target != null) viewModel.engine.attachPreview(target)
                    else viewModel.engine.detachPreview()
                },
                modifier = Modifier.fillMaxSize(),
            )

            RoiOverlay(
                roi = settings.roi,
                detectionBoxes = detection.boxes,
                triggered = detection.hit,
                editable = !dimmed,
                showDetections = settings.showDetectionOverlay,
                onRoiChange = viewModel::dragRoi,
                onRoiCommit = viewModel::commitRoi,
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (!dimmed) {
            TopHud(
                settings = settings,
                status = status,
                roiEnergy = detection.roiEnergy,
                backgroundEnergy = detection.backgroundEnergy,
                detectionLabel = detection.label,
                onOpenSettings = onOpenSettings,
                onOpenGallery = onOpenGallery,
                onFocus = { viewModel.engine.focusNow() },
                onDim = { dimmed = true },
                modifier = Modifier.align(Alignment.TopCenter),
            )

            BottomHud(
                viewModel = viewModel,
                settings = settings,
                armed = status.armed,
                saving = status.savingClip,
                lifecycle = status.lifecycle,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        if (dimmed) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.94f))
                    .clickable { dimmed = false },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (status.savingClip) "SAVING" else "ARMED",
                        color = if (status.savingClip) KickerRed else KickerGreen.copy(alpha = 0.5f),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "${status.clipsSavedThisSession} clips · tap to wake",
                        color = Color.White.copy(alpha = 0.25f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        status.error?.let { error ->
            Banner(
                text = error,
                color = KickerRed,
                onDismiss = viewModel.engine::clearMessage,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        if (status.error == null) {
            status.message?.let { message ->
                Banner(
                    text = message,
                    color = KickerOrange,
                    onDismiss = viewModel.engine::clearMessage,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 92.dp),
                )
            }
        }
    }
}

@Composable
private fun TopHud(
    settings: AppSettings,
    status: com.kickercam.capture.CaptureStatus,
    roiEnergy: Float,
    backgroundEnergy: Float,
    detectionLabel: String?,
    onOpenSettings: () -> Unit,
    onOpenGallery: () -> Unit,
    onFocus: () -> Unit,
    onDim: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.45f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StateChip(status = status)

        Spacer(Modifier.width(12.dp))

        Column {
            Text(
                text = buildString {
                    append("${status.effectiveSize.width}x${status.effectiveSize.height}")
                    append(" · ${status.effectiveFps}fps")
                    append(" · ${settings.codec.label.substringBefore(' ')}")
                    append(" · ${settings.bitrateMbps}Mbps")
                },
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
            )
            Text(
                text = buildString {
                    append("buffer ${"%.1f".format(status.bufferedSec)}s / ${settings.preRollSec.toInt()}s")
                    status.readout.zoomRatio?.let { append(" · zoom ${"%.1f".format(it)}x") }
                    status.readout.isoActual?.let { append(" · ISO $it") }
                    status.readout.exposureTimeNs?.let {
                        val fraction = if (it > 0) (1_000_000_000.0 / it).toInt() else 0
                        append(" · 1/$fraction")
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.65f),
            )
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.width(120.dp)) {
            Text(
                text = detectionLabel ?: settings.detectorMode.label,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.7f),
            )
            EnergyMeter(label = "box", value = roiEnergy, color = KickerGreen)
            EnergyMeter(label = "bg", value = backgroundEnergy, color = Color.White.copy(alpha = 0.35f))
        }

        Spacer(Modifier.weight(1f))

        if (!status.detectionAvailable) {
            AssistChip(
                onClick = {},
                label = { Text("detection off", style = MaterialTheme.typography.labelSmall) },
                colors = AssistChipDefaults.assistChipColors(labelColor = KickerRed),
            )
            Spacer(Modifier.width(8.dp))
        }

        IconButton(onClick = onFocus) {
            Icon(Icons.Filled.CenterFocusStrong, contentDescription = "Focus now", tint = Color.White)
        }
        IconButton(onClick = onDim) {
            Icon(Icons.Filled.BrightnessLow, contentDescription = "Dim screen", tint = Color.White)
        }
        IconButton(onClick = onOpenGallery) {
            Icon(Icons.Filled.PhotoLibrary, contentDescription = "Clips", tint = Color.White)
        }
        IconButton(onClick = onOpenSettings) {
            Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = Color.White)
        }
    }
}

@Composable
private fun StateChip(status: com.kickercam.capture.CaptureStatus) {
    val (text, color) = when {
        status.lifecycle == CaptureLifecycle.ERROR -> "ERROR" to KickerRed
        status.lifecycle != CaptureLifecycle.RUNNING -> "STARTING" to Color.Gray
        status.savingClip -> "SAVING ${status.currentClipMs / 1000}s" to KickerRed
        status.armed && status.armCountdownMs > 0 -> "ARMING ${status.armCountdownMs / 1000 + 1}" to KickerOrange
        status.armed -> "ARMED" to KickerGreen
        else -> "STANDBY" to Color.White.copy(alpha = 0.6f)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Filled.FiberManualRecord,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(12.dp),
        )
        Spacer(Modifier.width(6.dp))
        Column {
            Text(
                text = text,
                color = color,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                text = "${status.clipsSavedThisSession} saved",
                color = Color.White.copy(alpha = 0.5f),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun EnergyMeter(label: String, value: Float, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.45f),
            modifier = Modifier.width(20.dp),
        )
        LinearProgressIndicator(
            progress = { value.coerceIn(0f, 1f) },
            color = color,
            trackColor = Color.White.copy(alpha = 0.12f),
            modifier = Modifier.height(4.dp).weight(1f),
        )
    }
}

@Composable
private fun BottomHud(
    viewModel: CameraViewModel,
    settings: AppSettings,
    armed: Boolean,
    saving: Boolean,
    lifecycle: CaptureLifecycle,
    modifier: Modifier = Modifier,
) {
    val descriptor = remember(settings.cameraId) { viewModel.descriptorForCurrentLens() }
    val currentLensKey = viewModel.currentLens()?.key

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.45f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(viewModel.lensOptions, key = { it.key }) { option ->
                FilterChip(
                    selected = option.key == currentLensKey,
                    onClick = { viewModel.selectLens(option) },
                    label = {
                        Text(
                            option.label + if (option.experimental) " ⚠" else "",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (descriptor != null && descriptor.supportsZoomRatio) {
                Text(
                    text = "zoom ${"%.1f".format(settings.zoomRatio)}x",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier.width(74.dp),
                )
                Slider(
                    value = settings.zoomRatio,
                    onValueChange = { value -> viewModel.update { it.copy(zoomRatio = value) } },
                    valueRange = descriptor.zoomRange.lower..descriptor.zoomRange.upper,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(16.dp))
            } else {
                Spacer(Modifier.weight(1f))
            }

            Button(
                onClick = { viewModel.engine.manualTrigger() },
                enabled = lifecycle == CaptureLifecycle.RUNNING,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White.copy(alpha = 0.14f),
                    contentColor = Color.White,
                ),
            ) {
                Text("Save now")
            }

            Spacer(Modifier.width(12.dp))

            IconButton(
                onClick = { viewModel.engine.setArmed(!armed) },
                enabled = lifecycle == CaptureLifecycle.RUNNING,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = if (armed) KickerRed else KickerGreen,
                    contentColor = Color.Black,
                ),
                modifier = Modifier.size(56.dp),
            ) {
                Icon(
                    imageVector = if (armed) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                    contentDescription = if (armed) "Disarm" else "Arm",
                )
            }
        }

        AnimatedVisibility(visible = saving) {
            Text(
                text = "writing clip…",
                color = KickerRed,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun Banner(
    text: String,
    color: Color,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .padding(16.dp)
            .background(Color.Black.copy(alpha = 0.85f), RoundedCornerShape(12.dp))
            .clickable { onDismiss() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Column {
            Text(text = text, color = color, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = "tap to dismiss",
                color = Color.White.copy(alpha = 0.4f),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

private fun setBrightness(context: android.content.Context, value: Float) {
    val window = (context as? Activity)?.window ?: return
    window.attributes = window.attributes.apply { screenBrightness = value }
}

private fun restoreBrightness(context: android.content.Context) {
    val window = (context as? Activity)?.window ?: return
    window.attributes = window.attributes.apply {
        screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    }
}
