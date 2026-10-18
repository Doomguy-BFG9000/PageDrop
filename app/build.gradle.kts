/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.hilt.gradle)
    alias(libs.plugins.ksp)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.pagedrop"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.pagedrop"
        minSdk = 26
        targetSdk = 36
        versionCode = 14
        versionName = "0.9.0"

        testInstrumentationRunner = "app.pagedrop.HiltTestRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // These are release/build-time settings. They are never entered by an app user.
        // An absent property falls back to the built-in server; an empty one (-PNAME=) builds without a server.
        val pageDropSupabaseUrl = providers.gradleProperty("PAGEDROP_SUPABASE_URL").orNull
            ?: "https://bzvvpwykluklvvjyzytn.supabase.co"
        val pageDropSupabaseAnonKey = providers.gradleProperty("PAGEDROP_SUPABASE_ANON_KEY").orNull
            ?: "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImJ6dnZwd3lrbHVrbHZ2anl6eXRuIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODI5MDU1MDcsImV4cCI6MjA5ODQ4MTUwN30.NZB8HgGm_W6b3A6-QTbIlEOuCiIT1uCrAUYgG_CcGZI"
        buildConfigField("String", "PAGEDROP_SUPABASE_URL", "\"$pageDropSupabaseUrl\"")
        buildConfigField("String", "PAGEDROP_SUPABASE_ANON_KEY", "\"$pageDropSupabaseAnonKey\"")
    }

    signingConfigs {
        // Signing material lives outside the repo: env vars (CI) or a properties file. If neither
        // exists the release build is left unsigned (e.g. F-Droid signs it itself).
        val env = { name: String -> System.getenv(name) }
        val propsFile = file(
            providers.gradleProperty("PAGEDROP_KEYSTORE_PROPERTIES").orNull
                ?: "${System.getProperty("user.home")}/.pagedrop-release/keystore.properties"
        )
        val envPath = env("PAGEDROP_KEYSTORE_PATH")
        if (!envPath.isNullOrBlank()) {
            create("release") {
                storeFile = file(envPath)
                storePassword = env("PAGEDROP_KEYSTORE_PASSWORD")
                keyAlias = env("PAGEDROP_KEY_ALIAS")
                keyPassword = env("PAGEDROP_KEY_PASSWORD")
            }
        } else if (propsFile.isFile) {
            val props = Properties().apply { propsFile.inputStream().use { load(it) } }
            create("release") {
                storeFile = file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        getByName("release") {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
        aidl = false
        buildConfig = true
        shaders = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// slf4j-android calls android.util.Log, which isn't available in local unit tests.
configurations.matching { it.name.endsWith("UnitTestRuntimeClasspath") }.configureEach {
    exclude(group = "org.slf4j", module = "slf4j-android")
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // Core Android dependencies
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    // Hilt Dependency Injection
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    // Hilt and instrumented tests.
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.android.compiler)
    // Hilt and Robolectric tests.
    testImplementation(libs.hilt.android.testing)
    kspTest(libs.hilt.android.compiler)

    // Arch Components
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Compose
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material:material-icons-extended")
    // Tooling
    debugImplementation(libs.androidx.compose.ui.tooling)
    // Instrumented tests
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // Local tests: jUnit, coroutines, Android runner
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
    // In-process SSH server for host key pinning and key login tests
    testImplementation("org.apache.sshd:sshd-core:2.14.0")

    // Instrumented tests: jUnit rules and runners

    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    // Compose's transitive Espresso version predates Android 17's input API changes.
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")

    // Navigation
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)

    // SSH/SFTP client support for jailbroken Kindle transfer
    implementation("com.github.mwiede:jsch:2.28.3")

    // EPUB parsing for EPUB → MOBI conversion
    implementation("com.positiondev.epublib:epublib-core:3.1") {
        exclude(group = "org.slf4j")
        exclude(group = "xmlpull")
    }
    implementation("org.slf4j:slf4j-android:1.7.36")

    // PDF parsing for PDF → MOBI conversion
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // Server API client — OkHttp calls Supabase REST API directly
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation("org.jsoup:jsoup:1.23.2")

    // Archive extraction for dictionary/font installers
    implementation("org.apache.commons:commons-compress:1.27.1")

    // Image loading for UI
    implementation("io.coil-kt:coil-compose:2.7.0")

    // WorkManager for background transfers
    implementation(libs.work.runtime)
}
