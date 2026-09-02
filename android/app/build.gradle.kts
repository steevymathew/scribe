import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing is read from keystore.properties, which is gitignored and never
// committed. Without it the release variant falls back to the debug key so that the
// build still works on a fresh checkout — the produced APK is then a debug-signed
// release build and must not be handed out as a release.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "dev.smantics.scribe"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.smantics.scribe"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // docs/jni-contract.md: arm64-v8a only. Every current Android device Scribe
        // targets is arm64, and shipping one ABI keeps the sideloadable APK small.
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false // R8 would need keep rules for every JNI entry point
            signingConfig = if (keystoreProperties.isNotEmpty()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    /**
     * Two ways to be private, from one codebase.
     *
     *  standard — has INTERNET, used only for user-initiated model downloads, and keeps a
     *             visible ledger of every request it has ever made.
     *  airgap   — has no INTERNET permission at all. Not a promise: a property of the
     *             manifest, checkable with `aapt dump permissions` by anyone who doubts
     *             it. Models arrive with the APK or through the file picker.
     */
    flavorDimensions += "network"
    productFlavors {
        create("standard") {
            dimension = "network"
            buildConfigField("boolean", "NETWORK_ALLOWED", "true")
        }
        create("airgap") {
            dimension = "network"
            applicationIdSuffix = ".airgap"
            versionNameSuffix = "-airgap"
            buildConfigField("boolean", "NETWORK_ALLOWED", "false")
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
    kotlinOptions { jvmTarget = "17" }

    androidResources {
        // ggml weights are already quantised; deflating them saves nothing and costs a
        // slow inflate on the one path where the user is waiting.
        noCompress += listOf("bin")
    }

    packaging {
        jniLibs {
            // Keep the 16 KB-aligned .so files exactly as the cross-build produced them.
            useLegacyPackaging = false
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }

    sourceSets {
        getByName("main") {
            kotlin.srcDir("src/main/kotlin")
            jniLibs.srcDir("src/main/jniLibs")
        }
        getByName("test") { kotlin.srcDir("src/test/kotlin") }
    }
}

/**
 * Stage the bundled speech model into assets at build time.
 *
 * `models/` is gitignored — it holds a 57 MB binary that has no business in git history.
 * `tools/fetch-models.sh` re-downloads it. A missing file is not a build failure: the app
 * detects an absent bundled model and sends the user to the model manager instead, which
 * is also what the airgap flavour's users see when they supply their own.
 */
val stageModels = tasks.register<Copy>("stageModels") {
    from(rootProject.file("models")) { include("ggml-base.en-q5_1.bin") }
    into(layout.projectDirectory.dir("src/main/assets/models"))
}
tasks.named("preBuild") { dependsOn(stageModels) }

dependencies {
    implementation(project(":core"))

    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material3:material3-window-size-class")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.window:window:1.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
}
