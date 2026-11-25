// Copyright (c) 2026 Christian Reichardt
// SPDX-License-Identifier: MIT
// Assisted by AI (Claude).

import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.coffeeapp.droid"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.coffeeapp.droid"
        minSdk = 26
        targetSdk = 36
        // 2.0.0 started a new line after the 1.x numbering of the retired Pi app.
        // versionCode must only ever increase; an installed app refuses a lower one.
        // Bump with every installed fix or feature: minor for a feature, patch
        // for a fix-only build, versionCode +1 each time.
        // 2.1.0: idle release of the machine link; notification shows the final yield.
        // 2.2.0: machine link in short sessions (shot / Machine page only), HIGH
        //        priority and 2 s poll only during a shot, session logging.
        // 2.2.1: scan for the machine before each connect (status 133 after a restart).
        // 2.3.0: scan-only advertising probe every 15 min while the link is released.
        // 2.4.0: status-bar Bluetooth dot (filled = link up, ring = seen advertising).
        // 2.5.0: link up whenever the app is on screen (replaced before use).
        // 2.6.0: only a shot connects; probe every 60 s while the app is on screen.
        // 2.7.0: background probe removed.
        // 2.7.1: dead code removed (MONITOR demand, hidden Diagnostics probe).
        // 2.7.2: stale comments and UI text (Pi references, idle link wording).
        // 2.8.0: review fixes - stop retries and alerts, scale-lost handling,
        //        link demand only for an IDLE arm, threading, cloud lock and
        //        401 retry, dated crash-safe backups, logging with dates.
        // 2.14.0: Help - the user guide bundled and shown in a WebView.
        // 2.14.1: Help shows one section at a time, with a tab per section.
        // 2.14.2: guide screenshots cropped to what each section describes.
        // 2.14.3: first release build (debug-signed, so it updates in place).
        // 2.15.0: machine picture choice (La Marzocco photo, own photo, none).
        // 2.16.0: Bookoo scale support; one GATT client, a protocol per brand.
        // 2.16.1: logs written to Android/data (readable over adb on release).
        // 2.17.2: first public release - no seeded scale MAC or machine serial;
        //         a new-style Acaia falls back to the Umbra's characteristics.
        versionCode = 35
        versionName = "2.17.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // The published APKs are signed with a release key kept outside the repo,
    // described by ~/.android/shots-release.properties (storeFile relative to
    // that folder, storePassword, keyAlias, keyPassword). Without that file -
    // anyone else's checkout - release builds use the local debug key.
    // Android only updates an app in place with the key that signed it.
    val releaseKeyProps = File(System.getProperty("user.home"), ".android/shots-release.properties")
    val releaseKey = if (releaseKeyProps.isFile) {
        Properties().apply { releaseKeyProps.inputStream().use { load(it) } }
    } else {
        null
    }
    signingConfigs {
        if (releaseKey != null) {
            create("release") {
                storeFile = File(releaseKeyProps.parentFile, releaseKey.getProperty("storeFile"))
                storePassword = releaseKey.getProperty("storePassword")
                keyAlias = releaseKey.getProperty("keyAlias")
                keyPassword = releaseKey.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName(if (releaseKey != null) "release" else "debug")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        // android.util.Log (under FileLog) returns defaults in JVM tests
        // instead of throwing, so classes that log can be tested.
        unitTests.isReturnDefaultValues = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

/**
 * The user guide ships inside the app (Help). `docs/APP_GUIDE.html` stays the
 * only copy anyone edits; each build copies it into generated assets. The app
 * is always dark, so the bundled copy is pinned to the guide's dark theme, and
 * its Google Fonts link is dropped (the WebView blocks the network anyway;
 * the guide's font stacks fall back to the system fonts). A guide that no
 * longer has either marker fails the build rather than shipping unchanged.
 */
abstract class BundleGuideTask : DefaultTask() {
    @get:InputFile
    abstract val guide: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun bundle() {
        var html = guide.get().asFile.readText()
        val htmlTag = "<html lang=\"en\">"
        check(htmlTag in html) { "APP_GUIDE.html: no $htmlTag to pin the dark theme on" }
        html = html.replace(htmlTag, "<html lang=\"en\" data-theme=\"dark\">")
        val fonts = Regex("""<link rel="stylesheet" href="https://fonts\.googleapis\.com[^>]*>\r?\n?""")
        check(fonts.containsMatchIn(html)) { "APP_GUIDE.html: Google Fonts link not found" }
        html = html.replace(fonts, "")
        // One section at a time: Help loads the guide at #<section id> and
        // only the part holding that target is shown (the overview also gets
        // the intro and the footer). The huge scroll margin makes the jump to
        // the target clamp at the top, so a section opens on its screenshot,
        // not halfway down at its heading. Without a target (a browser, or no
        // fragment) the whole guide shows as before.
        val styleEnd = "</style>"
        check(html.split(styleEnd).size == 2) { "APP_GUIDE.html: expected exactly one $styleEnd" }
        html = html.replace(
            styleEnd,
            """
            /* Added by bundleGuide for Help in the app: one section at a time. */
            .wrap:has(:target) > :not(:has(:target)):not(:target) { display: none; }
            .wrap:has(#flow-h:target) > header.intro { display: grid; }
            .wrap:has(#flow-h:target) > footer { display: block; }
            .wrap:has(:target) > .screen { border-top: 0; padding-top: 0; }
            :target { scroll-margin-top: 100000px; }
            $styleEnd""".trimIndent(),
        )
        val out = outputDir.get().asFile
        out.mkdirs()
        out.resolve("APP_GUIDE.html").writeText(html)
    }
}

val bundleGuide = tasks.register<BundleGuideTask>("bundleGuide") {
    guide.set(rootProject.layout.projectDirectory.file("../docs/APP_GUIDE.html"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(bundleGuide, BundleGuideTask::outputDir)
    }
}

// Room writes each schema version here as JSON (committed), so a future
// migration has a baseline to diff against and the migration test has one
// to check.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.datastore.preferences)
    implementation(libs.security.crypto)

    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
