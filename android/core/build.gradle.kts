plugins {
    id("org.jetbrains.kotlin.jvm")
}

// Pure Kotlin/JVM. NOTHING here may import android.*, and a test enforces that.
// This mirrors the desktop rule from ROADMAP.md §4: "the engine must stay
// importable and runnable headless — the GUI is a client of the engine".
// It is also what makes the engine testable on a machine with no emulator.

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    testLogging {
        events("passed", "skipped", "failed")
    }
}
