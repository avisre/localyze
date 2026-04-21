plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val releaseKeystorePassword = providers.gradleProperty("LOCALASSISTANT_KEYSTORE_PASSWORD")
    .orElse(providers.environmentVariable("LOCALASSISTANT_KEYSTORE_PASSWORD"))
    .orNull
val releaseKeyPassword = providers.gradleProperty("LOCALASSISTANT_KEY_PASSWORD")
    .orElse(providers.environmentVariable("LOCALASSISTANT_KEY_PASSWORD"))
    .orNull

android {
    namespace = "com.localassistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.localassistant"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file("../localassistant-release.keystore")
            storePassword = releaseKeystorePassword
            keyAlias = "localassistant"
            keyPassword = releaseKeyPassword
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
            isDebuggable = false
            buildConfigField("Boolean", "USE_MOCK_ENGINE", "false")
            buildConfigField("Boolean", "USE_TEST_DOWNLOAD", "false")
        }
        debug {
            isMinifyEnabled = false
            isDebuggable = true
            // Set to false to use real Gemma 4 E4B model
            // Set to true for mock mode (development/CI/unsupported devices)
            buildConfigField("Boolean", "USE_MOCK_ENGINE", "false")
            // Set to false to download real model (3.65 GB)
            // Set to true for test download (small tokenizer.json file)
            buildConfigField("Boolean", "USE_TEST_DOWNLOAD", "false")
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

    // With Kotlin 2.1.0+, the Compose compiler is managed by the
    // org.jetbrains.kotlin.plugin.compose plugin — no need for composeOptions

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Core Android
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.10.1")

    // Compose BOM — updated to match AI Edge Gallery's version
    val composeBom = platform("androidx.compose:compose-bom:2025.01.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.8.9")

    // Hilt — updated to match AI Edge Gallery
    implementation("com.google.dagger:hilt-android:2.57.2")
    ksp("com.google.dagger:hilt-android-compiler:2.57.2")
    implementation("androidx.hilt:hilt-navigation-compose:1.3.0")

    // Room — updated to be compatible with Kotlin 2.1
    implementation("androidx.room:room-runtime:2.7.0")
    implementation("androidx.room:room-ktx:2.7.0")
    ksp("androidx.room:room-compiler:2.7.0")

    // ── AI / ML ──────────────────────────────────────────────────────────────
    // LiteRT-LM: The official Google runtime for running .litertlm models
    // This replaces the old MediaPipe GenAI (tasks-genai) which had NO NPU support.
    // LiteRT-LM natively supports Backend.NPU() for Hexagon HTP on Snapdragon.
    // Matches AI Edge Gallery's litertlm dependency.
    //
    // NOTE: No separate QNN dependencies needed! LiteRT-LM bundles the
    // necessary QNN/Hexagon libraries internally. The Gallery app does NOT
    // include separate qnn-runtime or qnn-litert-delegate deps.
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.10.0")

    // Camera
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    implementation("androidx.camera:camera-core:1.4.2")

    // Work Manager
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.1.7")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
