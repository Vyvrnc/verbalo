import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.0"
}

// Release signing comes from a properties file OUTSIDE the repo, mounted into the build
// container by tools/build-apk.sh (TN_SIGNING_PROPS). Without it the release APK stays
// unsigned rather than silently falling back to a debug key.
val signingPropsFile = System.getenv("TN_SIGNING_PROPS")?.let { file(it) }
val signingProps = Properties().apply {
    if (signingPropsFile?.exists() == true) signingPropsFile.inputStream().use { load(it) }
}

// --- QNN runtime (NPU) ----------------------------------------------------------------------
// From Maven Central com.qualcomm.qti:qnn-runtime (same as WhisperBench). Only what running
// pre-compiled context binaries on SM8750 (Hexagon v79) needs is copied out of the ~225 MB AAR:
//   libQnnHtp.so          QNN HTP backend (ARM side)
//   libQnnHtpV79Stub.so   FastRPC stub for v79
//   libQnnHtpV79Skel.so   DSP-side skel, loaded into the CDSP via ADSP_LIBRARY_PATH
//   libQnnSystem.so       QNN system API (context binary metadata)
// NOTICE.txt and LICENSE.pdf from the AAR are shipped in assets/qnn/.
val qnnRuntimeVersion = "2.51.0"
val qnnLibs = listOf("libQnnHtp.so", "libQnnHtpV79Stub.so", "libQnnHtpV79Skel.so", "libQnnSystem.so")
val qnnRuntime: Configuration by configurations.creating { isTransitive = false }
dependencies { qnnRuntime("com.qualcomm.qti:qnn-runtime:$qnnRuntimeVersion@aar") }
val qnnGenerated = layout.buildDirectory.dir("generated/qnn")
val extractQnnRuntime by tasks.registering(Sync::class) {
    from({ zipTree(qnnRuntime.singleFile) }) {
        include(qnnLibs.map { "jni/arm64-v8a/$it" })
        eachFile { relativePath = RelativePath(true, "jniLibs", "arm64-v8a", name) }
    }
    from({ zipTree(qnnRuntime.singleFile) }) {
        include("NOTICE.txt", "LICENSE.pdf")
        eachFile { relativePath = RelativePath(true, "assets", "qnn", name) }
    }
    includeEmptyDirs = false
    into(qnnGenerated)
}
// Every AGP task that reads the source-set asset/jniLib dirs must run after the extraction;
// Gradle 8 fails the build on undeclared reads.
tasks.configureEach {
    if (name != "extractQnnRuntime" &&
        listOf("assets", "jnilib", "lint", "nativelibs").any { name.contains(it, ignoreCase = true) }) {
        dependsOn(extractQnnRuntime)
    }
}

android {
    namespace = "dev.soupslurpr.transcribro"
    compileSdk = 36
    // Pinned to what the build image ships (tools/docker).
    buildToolsVersion = "35.0.0"
    ndkVersion = "27.3.13750724"

    defaultConfig {
        // Own id: installs next to upstream Transcribro and is signed with our key.
        applicationId = "cz.vyvrnc.transcribronpu"
        // The NPU path (QNN HTP v79) exists only on SM8750-class phones running Android 12+.
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_static",
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                    "-DCMAKE_BUILD_TYPE=Release",
                )
            }
        }
    }

    signingConfigs {
        create("release") {
            if (signingProps.isNotEmpty()) {
                storeFile = file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
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
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // QNN loads its stub/skel by path from nativeLibraryDir, so the .so files must be
            // extracted from the APK.
            useLegacyPackaging = true
            // QNN libraries ship pre-stripped, and *Skel.so files are Hexagon (DSP) ELF
            // binaries that the ARM llvm-strip must not touch.
            keepDebugSymbols += "**/libQnn*.so"
        }
    }
    sourceSets["main"].jniLibs.srcDir(qnnGenerated.map { it.dir("jniLibs") })
    sourceSets["main"].assets.srcDir(qnnGenerated.map { it.dir("assets") })

    androidResources {
        generateLocaleConfig = true
        localeFilters += listOf("en", "cs")
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (signingProps.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
        }
        getByName("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    dependenciesInfo {
        // Disables dependency metadata when building APKs.
        includeInApk = false
        // Disables dependency metadata when building Android App Bundles.
        includeInBundle = false
    }
}

dependencies {

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.2")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.navigation:navigation-compose:2.9.3")
    implementation("androidx.activity:activity-ktx:1.10.1")

    implementation(platform("androidx.compose:compose-bom:2025.07.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("com.google.accompanist:accompanist-permissions:0.37.3")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
}
