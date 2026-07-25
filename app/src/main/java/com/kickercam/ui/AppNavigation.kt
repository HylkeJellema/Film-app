package com.kickercam.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.kickercam.vm.CameraViewModel

private object Routes {
    const val CAMERA = "camera"
    const val SETTINGS = "settings"
    const val GALLERY = "gallery"
    const val PLAYER = "player"
}

@Composable
fun KickerCamRoot() {
    val context = androidx.compose.ui.platform.LocalContext.current

    val required = remember {
        listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    }
    var granted by remember {
        mutableStateOf(
            required.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            },
        )
    }
    var asked by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        // The camera is mandatory; the microphone is not, so a denied mic still lets you film.
        granted = result[Manifest.permission.CAMERA] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    }

    LaunchedEffect(Unit) {
        if (!granted && !asked) {
            asked = true
            launcher.launch(required.toTypedArray())
        }
    }

    if (!granted) {
        PermissionGate(onRequest = { launcher.launch(required.toTypedArray()) })
        return
    }

    val viewModel: CameraViewModel = viewModel()
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Routes.CAMERA) {
        composable(Routes.CAMERA) {
            CameraScreen(
                viewModel = viewModel,
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenGallery = { navController.navigate(Routes.GALLERY) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
        }
        composable(Routes.GALLERY) {
            GalleryScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onOpenClip = { clipId -> navController.navigate("${Routes.PLAYER}/$clipId") },
            )
        }
        composable(
            route = "${Routes.PLAYER}/{clipId}",
            arguments = listOf(navArgument("clipId") { type = NavType.StringType }),
        ) { entry ->
            PlayerScreen(
                viewModel = viewModel,
                clipId = entry.arguments?.getString("clipId").orEmpty(),
                onBack = { navController.popBackStack() },
            )
        }
    }
}

@Composable
private fun PermissionGate(onRequest: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("KickerCam needs the camera", style = MaterialTheme.typography.titleLarge)
            Text(
                "The camera runs continuously so the seconds before a trick can be saved. " +
                    "Microphone access is optional — without it clips are silent.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onRequest) { Text("Grant permissions") }
        }
    }
}
