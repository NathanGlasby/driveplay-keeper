plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseKeystorePath = System.getenv("DRIVEPLAY_KEYSTORE")
val releaseStorePassword = System.getenv("DRIVEPLAY_STORE_PASSWORD")
val releaseKeyAlias = System.getenv("DRIVEPLAY_KEY_ALIAS")
val releaseKeyPassword = System.getenv("DRIVEPLAY_KEY_PASSWORD")
val hasReleaseSigning = listOf(
    releaseKeystorePath,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

android {
    namespace = "za.co.driveplaykeeper"
    compileSdk = 34

    defaultConfig {
        applicationId = "za.co.driveplaykeeper"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(checkNotNull(releaseKeystorePath))
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
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
}

dependencies {
    implementation("androidx.car.app:app:1.4.0")
    testImplementation("junit:junit:4.13.2")
}

tasks.register<Copy>("packageDebugApk") {
    dependsOn("assembleDebug")
    from(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk"))
    into(rootProject.layout.projectDirectory.dir("artifacts"))
    rename { "DrivePlayKeeper-1.0.0-debug.apk" }
}

tasks.register<Copy>("packageReleaseApk") {
    dependsOn("assembleRelease")
    doFirst {
        check(hasReleaseSigning) {
            "Release signing variables are required before packaging the release APK."
        }
    }
    from(layout.buildDirectory.file("outputs/apk/release/app-release.apk"))
    into(rootProject.layout.projectDirectory.dir("artifacts"))
    rename { "DrivePlayKeeper-1.0.0.apk" }
}
