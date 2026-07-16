plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}

// OneDrive can lock Android's thousands of short-lived intermediate files.
// Keep generated intermediates in the system temp directory and copy only the
// finished APK back into this project.
val externalBuildRoot = File(System.getProperty("java.io.tmpdir"), "DrivePlayKeeperBuild")
layout.buildDirectory.set(File(externalBuildRoot, "root"))
subprojects {
    layout.buildDirectory.set(File(externalBuildRoot, name))
}
