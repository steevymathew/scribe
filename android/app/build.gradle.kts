import java.time.Duration
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
        versionCode = 3
        versionName = "0.3.0"

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
                // v2 alone installs everywhere Scribe runs, but v3 carries the key-rotation
                // lineage — without it, a future key change would orphan every installed copy.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
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
        unitTests.all {
            // Screenshot rendering needs an emulated x86-64 JVM on this host, so it lives
            // in its own `screenshotTest` task rather than slowing every run down.
            it.filter.excludeTestsMatching("*ScreenshotTest")
        }
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

/**
 * Screenshot rendering.
 *
 *   ./gradlew :app:screenshotTest                     — writes PNGs into app/build/screenshots
 *   ./gradlew :app:screenshotTest -PemulatedJvm=true  — forces the emulated x86-64 JVM
 *
 * Robolectric's native graphics runtime and Paparazzi's layoutlib are published for x86-64
 * only. On an x86-64 host this task simply works. **On this aarch64 build host it does
 * not**, and that was established rather than assumed: an x86-64 Temurin 17 was extracted
 * with `docker export` (see tools/setup-screenshot-jvm.sh), it runs fine under
 * qemu-user — Gradle probes it as a real JDK 17 and Robolectric gets as far as resolving
 * its SDK jars — and then the JVM aborts (SIGABRT, exit 134) while loading
 * `nativeruntime-4.13`'s shared library. Emulating a JVM is one thing; emulating a JVM
 * loading a graphics library that reaches into it is another.
 *
 * The task is kept, defaulting to the host JVM, because it is correct and useful on any
 * x86-64 machine. On this host it fails, and the consequence is stated plainly rather than
 * papered over: **the appearance of Scribe's UI has not been verified by anyone on this
 * machine, and is marked OWNER-VERIFY.** The Robolectric flow tests in
 * `ScribePanelTest` assert structure, reachability and screen-reader labelling, and make
 * no claim about how anything looks.
 */
val screenshotTest = tasks.register<Test>("screenshotTest") {
    group = "verification"
    description = "Renders the UI to PNGs. Needs an x86-64 host, or -PemulatedJvm=true."

    // The classpath is copied wholesale from the real unit-test task rather than
    // reassembled by hand. Hand-assembling it loses pieces AGP adds late — the generated
    // R classes, the merged resource config, the android.jar — and the failure arrives as
    // an unresolvable annotation type, which names nothing useful.
    val source = tasks.named<Test>("testStandardDebugUnitTest")
    dependsOn(source)
    testClassesDirs = files({ source.get().testClassesDirs })
    classpath = files({ source.get().classpath })

    if (providers.gradleProperty("emulatedJvm").orNull == "true") {
        // The path must end in bin/java: Gradle derives a toolchain from an `executable`
        // by stripping that suffix and probing it. The wrapper answers the probe by
        // forwarding to the emulated JVM, so Gradle sees a genuine Java 17 installation.
        executable = rootProject.file("tools/qemu-x86/x86-jvm/bin/java").absolutePath
        // Under qemu the JVM resolves user.home from the emulated image's passwd entry
        // rather than this account's, and Robolectric then tries to create its download
        // lock in a directory that does not exist.
        systemProperty("user.home", System.getProperty("user.home"))
        environment("HOME", System.getProperty("user.home"))
        maxHeapSize = "2g"
    }

    filter { includeTestsMatching("*ScreenshotTest") }
    systemProperty("scribe.screenshots", layout.buildDirectory.dir("screenshots").get().asFile.path)
    systemProperty("robolectric.graphicsMode", "NATIVE")
    timeout.set(Duration.ofMinutes(30))
    testLogging { events("passed", "failed", "skipped") }
    outputs.upToDateWhen { false }
}

dependencies {
    implementation(project(":core"))

    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    // LifecycleResumeEffect: setup state has to be re-read when the user comes
    // back from system settings, or the checklist keeps asking for what they just did.
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
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
    // Robolectric runs the Android framework on this machine's JVM. There is no Android
    // emulator for an aarch64 Linux host, so this is the only way the keyboard's
    // lifecycle and the panel's user flows get exercised automatically at all.
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
