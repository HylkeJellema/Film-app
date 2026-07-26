plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.kickercam"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kickercam"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"

        // The bundled ML Kit models ship a native inference engine per ABI, which is most of the
        // APK. Every phone this app targets is arm64, so the other three are pure dead weight.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    lint {
        abortOnError = true
        // Each of these is a deliberate decision, documented in the README. Silencing them keeps a
        // real lint error visible instead of buried in 30 lines of known noise.
        disable += setOf(
            // Dependency versions are pinned to a known-good, mutually compatible set.
            "GradleDependency",
            "AndroidGradlePluginVersion",
            // The viewfinder is locked to landscape on purpose; this is a tripod app.
            "DiscouragedApi",
            // arm64 only: shipping four ABIs quadrupled the APK for the bundled ML native libs.
            "ChromeOsAbiSupport",
        )
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)

    implementation(libs.mlkit.objectdetection)
    implementation(libs.mlkit.posedetection)

    testImplementation(libs.junit)
}
