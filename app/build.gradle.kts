plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "fi.ak.smartvoice"
    compileSdk = 36
    defaultConfig {
        applicationId = "fi.ak.smartvoice"
        minSdk = 30
        targetSdk = 36
        // versionName says which slice this is, because APKs are installed by hand on the phone and
        // "which build is on there?" has to be answerable from Android's own app info screen.
        versionCode = 14
        versionName = "1.11-ui1"

        // The local tier's runtime AAR carries four ABIs (arm64-v8a, armeabi-v7a, x86, x86_64)
        // at ~48 MB. The target device is arm64 (D-009) and there is no usable emulator on the
        // build host (/dev/kvm is root-only, docs/build.md), so the other three are dead weight
        // in the APK. Re-add x86_64 here if a software-emulated AVD ever becomes useful.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
      compose = true
      aidl = false
      buildConfig = false
      shaders = false
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
      // SPIKE ONLY (D-029), and the likely reason the first probe build failed with
      // "Llm create failed: Invalid plugin": GenieX's plugin registry works by *scanning a
      // directory* for plugin .so files, and AGP's modern default stores them uncompressed inside
      // the APK without extracting them — so the native library directory is empty and the scan
      // finds nothing. Extracting the libraries gives the SDK a real directory to scan.
      //
      // The qairt exclusions are deliberately GONE from this build. Guessing which of the 52 native
      // libraries matter cost one round trip already; the whole 215 MB ships instead, and if the
      // APK size ever matters again we will filter it from a working baseline rather than into one.
      jniLibs {
        useLegacyPackaging = true
      }
    }
}

kotlin {
    jvmToolchain(17)
}

/**
 * A missing AAR is the one failure mode a fresh clone hits, and Gradle's own "file not found"
 * message does not say what to do about it. Fetch it with tools/fetch_sherpa_aar.sh.
 *
 * Declared before `dependencies` on purpose: Kotlin script properties initialise in source order,
 * and the dependency block below runs immediately, so a declaration further down would be null.
 */
val sherpaOnnxVersion = libs.versions.sherpaOnnx.get()
val sherpaOnnxAar = layout.projectDirectory.file("libs/sherpa-onnx-$sherpaOnnxVersion.aar")
require(sherpaOnnxAar.asFile.exists()) {
    "Missing ${sherpaOnnxAar.asFile}. Run tools/fetch_sherpa_aar.sh (downloads sherpa-onnx " +
        "$sherpaOnnxVersion, ~48 MB, sha256-verified) — see docs/build.md."
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation("androidx.compose.material:material-icons-core")
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)

  // Navigation
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)

  // v1: talking to local-voice-stack (PLAN §14, docs/contract.md).
  // HTTP itself is java.net.HttpURLConnection on purpose: okhttp-android 5.5.0 requires
  // compileSdk 37, and this project stays on the template's AGP 9.0.1 / compileSdk 36 so that a
  // normal Android Studio can open it. The multipart body is ours (net/MultipartForm.kt), which
  // also makes the wire format unit-testable.
  implementation(libs.kotlinx.serialization.json)

  // v2 local tier: the on-device streaming ASR runtime (D-020, D-026). Not a Maven coordinate —
  // see tools/fetch_sherpa_aar.sh, which pins the same version as the catalog entry above.
  implementation(files(sherpaOnnxAar))

  // SPIKE ONLY (branch spike/geniex-npu, D-029): the NPU experiment. GenieX on Maven Central for
  // the Hexagon NPU. Not decided for chunk 5 — the spike decides that.
  implementation("com.qualcomm.qti:geniex-android:0.4.0")
}
