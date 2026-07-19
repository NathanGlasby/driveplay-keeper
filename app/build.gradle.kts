import java.security.KeyStore
import java.security.MessageDigest

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
val expectedReleaseCertificateSha256 =
    "e07bdbf14ff6a80c419595758cf38e8e3473cf62edb710c877fe11d7d6091349"

val externalBuildRoot = providers.environmentVariable("DRIVEPLAY_BUILD_DIR")
    .orElse(File(System.getProperty("java.io.tmpdir"), "DrivePlayKeeperBuild").absolutePath)
layout.buildDirectory.set(file("${externalBuildRoot.get()}/app"))

android {
    namespace = "za.co.driveplaykeeper"
    compileSdk = 34

    defaultConfig {
        applicationId = "za.co.driveplaykeeper"
        minSdk = 28
        targetSdk = 34
        versionCode = 2
        versionName = "1.0.1"

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

val verifyReleaseSigningKey = tasks.register("verifyReleaseSigningKey") {
    group = "verification"
    description = "Checks that release builds use the same certificate as v1.0.0."

    doLast {
        check(hasReleaseSigning) {
            "Release signing variables are required before building a release APK."
        }

        val keystoreFile = file(checkNotNull(releaseKeystorePath))
        check(keystoreFile.isFile) {
            "Release keystore not found: ${keystoreFile.absolutePath}"
        }

        val keystore = KeyStore.getInstance("JKS")
        keystoreFile.inputStream().use { input ->
            keystore.load(input, checkNotNull(releaseStorePassword).toCharArray())
        }
        val certificate = keystore.getCertificate(checkNotNull(releaseKeyAlias))
            ?: error("Release key alias was not found in the configured keystore.")
        val actualCertificateSha256 = MessageDigest.getInstance("SHA-256")
            .digest(certificate.encoded)
            .joinToString("") { byte -> "%02x".format(byte) }

        check(actualCertificateSha256 == expectedReleaseCertificateSha256) {
            "Release certificate does not match v1.0.0. Refusing to build an incompatible APK."
        }
    }
}

tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(verifyReleaseSigningKey)
}

tasks.register<Copy>("packageDebugApk") {
    dependsOn("assembleDebug")
    from(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk"))
    into(rootProject.layout.projectDirectory.dir("artifacts"))
    rename { "DrivePlayKeeper-1.0.1-debug.apk" }
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
    rename { "DrivePlayKeeper-1.0.1.apk" }
}
