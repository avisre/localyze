plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
    id("org.owasp.dependencycheck")
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
val debugUseTestDownload = providers.gradleProperty("LOCALYZE_USE_TEST_DOWNLOAD")
    .orElse("false")
    .map { it.equals("true", ignoreCase = true).toString() }
    .get()
android {
    namespace = "com.localyze"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.localyze"
        minSdk = 28
        targetSdk = 35
            versionCode = 13
            versionName = "1.1.6"

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
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
            isDebuggable = false
            buildConfigField("String", "PREMIUM_SUBSCRIPTION_PRODUCT_ID", "\"$premiumSubscriptionProductId\"")
            buildConfigField("Boolean", "USE_TEST_DOWNLOAD", "false")
        }
        debug {
            isMinifyEnabled = false
            isDebuggable = true
            buildConfigField("String", "PREMIUM_SUBSCRIPTION_PRODUCT_ID", "\"$premiumSubscriptionProductId\"")
            buildConfigField("Boolean", "USE_TEST_DOWNLOAD", debugUseTestDownload)
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

    // Crashlytics mapping upload requires a real Firebase project.
    // Using placeholder project ID — disable the upload to unblock release builds.
    firebaseCrashlytics {
        mappingFileUploadEnabled = false
    }

    lint {
        // Fail release builds on hard errors, warn (don't fail) on warnings.
        abortOnError = true
        warningsAsErrors = false
        checkReleaseBuilds = true
        // Generate a baseline so we don't fail on pre-existing issues — new
        // issues introduced after the baseline will still be flagged.
        baseline = file("lint-baseline.xml")
        // HTML + XML reports under app/build/reports/lint-results-*.{html,xml}
        htmlReport = true
        xmlReport = true
        // Disabled checks that don't apply to this project. Keep this list
        // small and justified.
        disable += setOf(
            // Hilt-injected fields are sometimes flagged.
            "InvalidPackage",
            // Compose-only project; we don't ship XML layouts.
            "MissingDefaultResource"
        )
    }
}

dependencies {
    // Core Android
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
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

    // Work Manager
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // Google Play Billing for Localyze Premium subscriptions.
    implementation("com.android.billingclient:billing-ktx:8.3.0")

    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.18.3")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.1.7")

    // Paging3 for efficient message list loading
    implementation("androidx.paging:paging-runtime-ktx:3.3.6")
    implementation("androidx.paging:paging-compose:3.3.6")

    // SQLCipher for encrypted database at rest
    implementation("net.zetetic:sqlcipher-android:4.9.0")
    implementation("androidx.sqlite:sqlite:2.4.0")
    implementation("androidx.sqlite:sqlite-ktx:2.4.0")

    // Play Integrity API for purchase verification
    implementation("com.google.android.play:integrity:1.4.0")

    // LiteRT-LM: The official Google runtime for running .litertlm models
    // This replaces the old MediaPipe GenAI (tasks-genai) which had NO NPU support.
    // LiteRT-LM natively supports Backend.NPU() for Hexagon HTP on Snapdragon.
    // Matches AI Edge Gallery's litertlm dependency.
    //
    // NOTE: No separate QNN dependencies needed! LiteRT-LM bundles the
    // necessary QNN/Hexagon libraries internally. The Gallery app does NOT
    // include separate qnn-runtime or qnn-litert-delegate deps.
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.10.0")

    // Firebase Crashlytics for crash reporting (opt-out)
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-crashlytics-ktx")
    implementation("com.google.firebase:firebase-analytics-ktx")

    // LeakCanary for debug builds to detect activity context leaks in singletons
    debugImplementation("com.squareup.leakcanary:leakcanary-android:2.14")

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
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}

// Placeholder Firebase project — disable Crashlytics mapping upload so release
// builds don't fail attempting to contact a non-existent Firebase project.
afterEvaluate {
    tasks.matching { it.name.startsWith("uploadCrashlyticsMappingFile") }.configureEach {
        enabled = false
    }
}
