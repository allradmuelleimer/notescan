import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.chaquopy)
}

android {
    namespace = "com.nicedevelopments.vomblatt"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nicedevelopments.vomblatt"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"

        // Python (Chaquopy) und ONNX Runtime gibt es nativ nur für diese Architekturen:
        // arm64 für echte Handys, x86_64 für den Emulator.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Klavier-Samples liegen schon als OGG vor - nicht noch einmal komprimieren.
    androidResources {
        noCompress += listOf("ogg", "onnx")
        // Muss zu res/xml/locales_config.xml und den res/values-<sprache>-Ordnern passen –
        // was hier fehlt, wirft AGP aus dem Bundle, und Google Play meldet die Sprache nicht.
        localeFilters += listOf(
            "de", "en", "fr", "es", "it", "sv", "ru", "ar", "zh",
            "ja", "ko", "hi", "pl", "cs", "nb", "fi", "da", "id", "th", "pt", "nl",
        )
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

// Notenerkennung homr läuft in Python 3.10 – die neueste Version, für die Chaquopy OpenCV anbietet.
chaquopy {
    defaultConfig {
        version = "3.10"
        // Python 3.10 für den Build: Pfad aus der Umgebungsvariable CHAQUOPY_PYTHON, sonst „python3.10“ im PATH
        buildPython(System.getenv("CHAQUOPY_PYTHON") ?: "python3.10")
        pip {
            install("numpy==1.26.2")
            install("opencv-python-headless==4.5.1.48")
            install("pillow==11.0.0")
            install("typing_extensions")
        }
        pyc { src = false }
    }
    sourceSets {
        getByName("main") { srcDir("src/main/python-android") }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    // Einmalkauf „NoteScan Pro“ – siehe pro/Pro.kt
    implementation(libs.play.billing)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.onnxruntime.android)
    // Titel vom Blatt lesen – gebündeltes Modell, läuft offline ohne Play-Dienste-Download.
    implementation(libs.mlkit.text.recognition)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
