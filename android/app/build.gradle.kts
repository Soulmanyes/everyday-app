// AGP 9 compiles Kotlin itself (built-in Kotlin), so org.jetbrains.kotlin.android is not applied.
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
    // Reads google-services.json (Firebase project, both app ids) for instant updates.
    alias(libs.plugins.google.services)
}

android {
    namespace = "app.everyday.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.everyday.android"
        minSdk = 28
        targetSdk = 37
        // Bump both for every release: the app compares versionCode with the server's version.json.
        versionCode = 15
        versionName = "2.5.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // The release key lives outside the repo (~/.everyday, backed up off the PC). Without it,
    // release builds are unsigned and can't be installed.
    val keystoreFile = File(System.getProperty("user.home"), ".everyday/keystore.properties")
    signingConfigs {
        if (keystoreFile.exists()) {
            val props = Properties().apply { keystoreFile.inputStream().use { load(it) } }
            create("release") {
                storeFile = File(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        // What the household installs from the download link.
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release")
            resValue("string", "app_name", "Everyday")
            buildConfigField("String", "DEFAULT_SERVER_URL", "\"https://everyday-home.duckdns.org\"")
            buildConfigField("boolean", "CHECK_FOR_UPDATES", "true")
            // Join links (everyday://join?code=...) open this app.
            manifestPlaceholders["joinScheme"] = "everyday"
            buildConfigField("String", "JOIN_SCHEME", "\"everyday\"")
        }
        // The development app: installs next to the release one and uses the throwaway test server
        // (reached through `adb reverse` to the SSH tunnel), so unfinished features never touch real data.
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-dev"
            resValue("string", "app_name", "Everyday (dev)")
            buildConfigField("String", "DEFAULT_SERVER_URL", "\"http://127.0.0.1:18080\"")
            buildConfigField("boolean", "CHECK_FOR_UPDATES", "false")
            // Its own scheme, so join links for the real app never open the dev app.
            manifestPlaceholders["joinScheme"] = "everyday-dev"
            buildConfigField("String", "JOIN_SCHEME", "\"everyday-dev\"")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }
}

kotlin {
    jvmToolchain(21)
}

room {
    // Exported schemas are committed so every database version bump can get a tested migration.
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.work.runtime.ktx)
    // Instant updates: data-only "sync now" pushes from the server.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.room.testing)

    constraints {
        // room-testing needs 1.8.1, and the test APK must use the app's version (1.7.3 via navigation).
        implementation(libs.kotlinx.serialization.core) { because("align with room-testing for MigrationTest") }
        // Firebase brings fragment 1.1.0, which mishandles the notification-permission request (lint fails release builds).
        implementation(libs.androidx.fragment) { because("ActivityResult APIs need fragment 1.3.0+") }
    }
}
