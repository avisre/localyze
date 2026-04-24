plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val releaseKeystorePassword = providers.environmentVariable("LOCALYZE_KEYSTORE_PASSWORD")
    .orElse(providers.gradleProperty("LOCALYZE_KEYSTORE_PASSWORD"))
    .orNull
val releaseKeyPassword = providers.environmentVariable("LOCALYZE_KEY_PASSWORD")
    .orElse(providers.gradleProperty("LOCALYZE_KEY_PASSWORD"))
    .orNull
val releaseKeystoreFile = providers.environmentVariable("LOCALYZE_KEYSTORE_FILE")
    .orElse(providers.gradleProperty("LOCALYZE_KEYSTORE_FILE"))
    .map { file(it) }
    .orNull ?: file("../localyze-release.keystore")
val premiumSubscriptionProductId = providers.gradleProperty("LOCALYZE_PREMIUM_SUBSCRIPTION_PRODUCT_ID")
    .orElse("localyze_premium_yearly")
    .get()
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")

android {
    namespace = "com.localyze"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.localyze"
        minSdk = 28
        targetSdk = 35
            versionCode = 4
            versionName = "1.0.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            storeFile = releaseKeystoreFile
            storePassword = releaseKeystorePassword
            keyAlias = "localyze"
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
            buildConfigField("String", "PREMIUM_SUBSCRIPTION_PRODUCT_ID", "\"$premiumSubscriptionProductId\"")
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
            buildConfigField("String", "PREMIUM_SUBSCRIPTION_PRODUCT_ID", "\"$premiumSubscriptionProductId\"")
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
    // org.jetbrains.kotlin.plugin.compose plugin â€” no need for composeOptions

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

    // Compose BOM â€” updated to match AI Edge Gallery's version
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

    // Hilt â€” updated to match AI Edge Gallery
    implementation("com.google.dagger:hilt-android:2.57.2")
    ksp("com.google.dagger:hilt-android-compiler:2.57.2")
    implementation("androidx.hilt:hilt-navigation-compose:1.3.0")

    // Room â€” updated to be compatible with Kotlin 2.1
    implementation("androidx.room:room-runtime:2.7.0")
    implementation("androidx.room:room-ktx:2.7.0")
    ksp("androidx.room:room-compiler:2.7.0")

    // â”€â”€ AI / ML â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    // LiteRT-LM: The official Google runtime for running .litertlm models
    // This replaces the old MediaPipe GenAI (tasks-genai) which had NO NPU support.
    // LiteRT-LM natively supports Backend.NPU() for Hexagon HTP on Snapdragon.
    // Matches AI Edge Gallery's litertlm dependency.
    //
    // NOTE: No separate QNN dependencies needed! LiteRT-LM bundles the
    // necessary QNN/Hexagon libraries internally. The Gallery app does NOT
    // include separate qnn-runtime or qnn-litert-delegate deps.
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.10.0")

    // Work Manager
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // Google Play Billing for Localyze Premium subscriptions.
    implementation("com.android.billingclient:billing-ktx:8.3.0")

    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.1.7")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    testImplementation("org.mockito:mockito-inline:5.2.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
