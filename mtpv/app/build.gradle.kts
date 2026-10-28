import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The commit this build comes from; null without git. No "-dirty" mark: F-Droid edits its checkout
// before building, and its build must match the signed APK byte for byte (reproducible build).
val gitCommit = runCatching {
    providers.exec {
        commandLine("git", "describe", "--always", "--abbrev=7", "--exclude=*")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim()
}.getOrNull()?.takeIf { it.isNotEmpty() }

android {
    namespace = "io.github.ernstkl.multitabletpdfviewer"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.ernstkl.multitabletpdfviewer"
        minSdk = 29
        targetSdk = 35
        versionCode = 3
        versionName = "0.10.1"
        buildConfigField("String", "GIT_COMMIT", gitCommit?.let { "\"$it\"" } ?: "null")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Signs release builds for testers when keystore.properties exists (kept out of git).
    // Without it the release APK stays unsigned, as F-Droid wants it.
    val keystoreProperties = rootProject.file("keystore.properties")
    val releaseSigning = if (keystoreProperties.exists()) {
        val props = Properties().apply { keystoreProperties.inputStream().use { load(it) } }
        signingConfigs.create("release") {
            storeFile = file(props.getProperty("storeFile"))
            storePassword = props.getProperty("storePassword")
            keyAlias = props.getProperty("keyAlias")
            keyPassword = props.getProperty("keyPassword")
        }
    } else null

    buildTypes {
        // With keystore.properties, debug builds get the release key too: they install over a release
        // build and back without uninstalling, which would delete the app's data.
        debug {
            releaseSigning?.let { signingConfig = it }
            versionNameSuffix = "-debug"
        }
        release {
            signingConfig = releaseSigning
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    // F-Droid: no Google-encrypted dependency metadata blob in the APK.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("sh.calvin.reorderable:reorderable:2.4.3")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
