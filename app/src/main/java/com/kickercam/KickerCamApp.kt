package com.kickercam

import android.app.Application
import com.kickercam.camera.CameraCapabilities
import com.kickercam.settings.SettingsStore
import com.kickercam.storage.ClipRepository

/** Minimal service locator — this app has three singletons and no need for a DI framework. */
class KickerCamApp : Application() {

    val repository: ClipRepository by lazy { ClipRepository(this) }
    val capabilities: CameraCapabilities by lazy { CameraCapabilities(this) }
    val settingsStore: SettingsStore by lazy { SettingsStore(this) }
}
