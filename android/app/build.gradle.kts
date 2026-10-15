// AGP 9 compiles Kotlin itself (built-in Kotlin), so org.jetbrains.kotlin.android is not applied.
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.util.Properties
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
    // Reads google-services.json (Firebase project, both app ids) for instant updates.
    alias(libs.plugins.google.services)
    // Lists the open-source libraries in the APK and their licenses (res/raw/aboutlibraries.json, Settings → Open source licenses).
    alias(libs.plugins.aboutlibraries)
}

android {
    namespace = "app.everyday.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.everyday.android"
        minSdk = 28
        targetSdk = 37
        // Bump both for every release: the app compares versionCode with the server's version.json.
        versionCode = 21
        versionName = "2.6.5"
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
            buildConfigField("String", "DEFAULT_SERVER_URL", "\"https://szekbal.duckdns.org\"")
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

aboutLibraries {
    collect {
        // Overrides: protobuf's real BSD notice instead of the SPDX template with "<year> <owner>".
        configPath = file("aboutlibraries")
    }
    license {
        // Fail the build if a dependency brings a license nobody has checked (e.g. GPL), so the APK
        // never ships something it can't. Add a license here only after checking what it requires.
        strictMode = com.mikepenz.aboutlibraries.plugin.StrictMode.FAIL
        allowedLicenses.addAll("Apache-2.0", "BSD-3-Clause", "protobuf-bsd-3-clause", "ASDKL")
    }
}

// Every license must ship with its text (only Google's SDK terms are a link). The plugin downloads
// texts at build time, so they're committed in aboutlibraries/licenses/ instead; a newly allowed
// license needs its text there too, or this fails the build.
tasks.matching { it.name.startsWith("exportLibraryDefinitions") }.configureEach {
    doLast {
        outputs.files.asFileTree.matching { include("**/aboutlibraries.json") }.forEach { file ->
            @Suppress("UNCHECKED_CAST")
            val licenses = (JsonSlurper().parse(file) as Map<String, Any>)["licenses"] as Map<String, Map<String, Any?>>
            val missing = licenses.filter { (key, license) -> key != "ASDKL" && (license["content"] as String?).isNullOrBlank() }.keys
            check(missing.isEmpty()) { "No license text for $missing: add it to app/aboutlibraries/licenses/" }
        }
    }
}

/**
 * Google Play services and Firebase AARs compile other projects' code in (Guava, Dagger, ...) and ship
 * those notices in `third_party_licenses.json/.txt`, which AboutLibraries doesn't read. This collects
 * them from the variant's AARs into `res/raw/embedded_licenses.json` (shown in Open source licenses).
 */
abstract class EmbeddedLicensesTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val aars: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun collect() {
        // Same text under several names (e.g. Guava JDK5 and JDK7): listed once, with all its names.
        val namesByText = sortedMapOf<String, MutableSet<String>>()
        aars.files.filter { it.name.endsWith(".aar") }.forEach { aar ->
            ZipFile(aar).use { zip ->
                val index = zip.getEntry("third_party_licenses.json") ?: return@use
                val text = zip.getEntry("third_party_licenses.txt") ?: return@use
                val bytes = zip.getInputStream(text).readBytes()
                @Suppress("UNCHECKED_CAST")
                val entries = JsonSlurper().parse(zip.getInputStream(index)) as Map<String, Map<String, Int>>
                entries.forEach { (name, range) ->
                    // start and length count bytes, not characters.
                    val start = range.getValue("start")
                    val notice = String(bytes, start, range.getValue("length"), Charsets.UTF_8).trim()
                    namesByText.getOrPut(notice) { sortedSetOf() }.add(name)
                }
            }
        }
        val notices = namesByText.map { (text, names) -> mapOf("names" to names.toList(), "text" to text) }
            .sortedBy { (it["names"] as List<*>).first().toString().lowercase() }
        val out = outputDir.get().dir("raw").asFile.apply { mkdirs() }
        File(out, "embedded_licenses.json").writeText(JsonOutput.toJson(notices))
    }
}

androidComponents {
    onVariants { variant ->
        val name = variant.name.replaceFirstChar { it.uppercase() }
        val task = tasks.register<EmbeddedLicensesTask>("collectEmbeddedLicenses$name") {
            aars.from(
                configurations.named("${variant.name}RuntimeClasspath").map { classpath ->
                    classpath.incoming.artifactView {
                        lenient(true)
                        attributes { attribute(Attribute.of("artifactType", String::class.java), "aar") }
                    }.files
                },
            )
        }
        variant.sources.res?.addGeneratedSourceDirectory(task, EmbeddedLicensesTask::outputDir)
    }
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
