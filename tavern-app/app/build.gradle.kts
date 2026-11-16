plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseStorePassword = providers.gradleProperty("STCTRL_KEYSTORE_PASSWORD")
val releaseKeyPassword = providers.gradleProperty("STCTRL_KEY_PASSWORD")

// Where the embedding model is fetched from when a reader picks vectors. Overridable so a local
// server can stand in for the release asset while the download path is being tested; the default
// is the release that carries it, tagged independently of app versions because the model changes
// on its own schedule.
val memoryModelUrl = (providers.gradleProperty("memoryModelUrl").orNull
    ?: "https://github.com/wancDDY/ST-Ctrl/releases/download/memory-model-v1/memory-model.zip")

android {
    namespace = "com.tavern.app"
    compileSdk = 34
    ndkVersion = "29.0.14206865"

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("keystore/release.jks")
            // Passwords come from gradle.properties (local, gitignored) or -P.
            storePassword = releaseStorePassword.orNull
            keyAlias = (properties["STCTRL_KEY_ALIAS"] as? String) ?: "stctrl"
            keyPassword = releaseKeyPassword.orNull
        }
    }

    defaultConfig {
        // A second installation under its own package name, for checking what a reader who has
        // never run the app sees — the model download in particular, which is invisible once the
        // files are on disk. Nothing in the shipped package differs; only this line is chosen at
        // build time, and only when asked for.
        applicationId = if (project.hasProperty("betaId")) "com.stctrl.mobile.beta" else "com.stctrl.mobile"
        minSdk = 26
        targetSdk = 34
        versionCode = 8
        versionName = "2.1.0"

        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }

        buildConfigField("String", "MEMORY_MODEL_URL", "\"$memoryModelUrl\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }

    // Dev-only pristine core backup lives in tools/orig/ (NOT src/main/assets)
    // so the ~144MB tavern-core.zip.orig never gets packed into the APK.
    // AGP has no reliable way to exclude an assets file, hence the placement.

    splits {
        abi {
            isEnable = true
            isUniversalApk = false
            reset()
            // Both ABIs ship the self-built Node v24.20.0 libnode.
            include("arm64-v8a", "armeabi-v7a")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    androidResources {
        // tavern-core.zip is already deflated; leaving it out of noCompress lets
        // the APK deflate the nested archive again for a few more MB.
        //
        // The embedding model is stored deflated too: 24 MB of weights compress to 16 MB, and
        // the cost is one unpack into the app's own directory on first launch, after which the
        // loader reads it from disk. Keeping it uncompressed to save those few hundred
        // milliseconds put 8 MB on every download for a wait nobody sees twice.
        noCompress += listOf("js")
    }

    sourceSets {
        // The model lives outside assets/ so that leaving it out is a matter of not adding the
        // folder, rather than packaging it and then trying to strip it back out.
        //
        // Bundled by default: it compresses to 16 MB and saves the reader a download and a
        // network round trip at the moment they pick vectors. `-PnoMemoryModel` produces the
        // shape without it, where the app offers the download instead — the code is the same in
        // both, and the page decides by asking whether the files are there.
        if (!project.hasProperty("noMemoryModel")) {
            getByName("main").assets.srcDir("models")
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

gradle.taskGraph.whenReady {
    val needsReleaseSigning = allTasks.any { task ->
        task.project == project && (
            task.name == "assembleRelease" ||
                task.name == "bundleRelease" ||
                (task.name.startsWith("package") && task.name.endsWith("Release"))
            )
    }
    if (needsReleaseSigning && (!releaseStorePassword.isPresent || !releaseKeyPassword.isPresent)) {
        throw GradleException(
            "STCTRL_KEYSTORE_PASSWORD/STCTRL_KEY_PASSWORD are not configured. " +
                "Set the signing passwords in tavern-app/gradle.properties (gitignored)."
        )
    }
}

dependencies {
    // Compose
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")

    // Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")

    // WebView
    implementation("androidx.webkit:webkit:1.9.0")

    // Security
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // On-device sentence embeddings for memory recall. Vendored as a local AAR because this
    // project builds offline: the artifact is only ever fetched once, into libs/, and the build
    // never reaches the network for it. The model itself is chosen at runtime (bundled for test
    // builds, downloaded otherwise), so this dependency does not imply the model is in the APK.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.17.1")

    // Material Icons Extended
    implementation("androidx.compose.material:material-icons-extended")

    // Compose Navigation
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Image loading
    implementation("io.coil-kt:coil-compose:2.5.0")

    // Test
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    // The mockable android.jar returns defaults for org.json, so the real implementation is
    // needed to test code that reads or writes SillyTavern's settings.
    testImplementation("org.json:json:20240303")
}
