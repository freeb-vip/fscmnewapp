/*
 * Copyright 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import com.google.samples.apps.nowinandroid.NiaBuildType
import com.android.build.api.variant.BuildConfigField
import org.gradle.api.GradleException
import java.io.StringReader
import java.util.Properties

plugins {
    alias(libs.plugins.nowinandroid.android.application)
    alias(libs.plugins.nowinandroid.android.application.compose)
    alias(libs.plugins.nowinandroid.android.application.flavors)
    alias(libs.plugins.nowinandroid.android.application.jacoco)
    alias(libs.plugins.nowinandroid.hilt)
    alias(libs.plugins.baselineprofile)
    alias(libs.plugins.roborazzi)
    alias(libs.plugins.kotlin.serialization)
}

val localProperties = providers.fileContents(
    isolated.rootProject.projectDirectory.file("local.properties"),
).asText.map { text ->
    Properties().apply {
        load(StringReader(text))
    }
}.orElse(Properties())

val appVersionCode = localProperties.map { properties ->
    val configured = properties.getProperty("FSCM_VERSION_CODE") ?: return@map 9
    configured.trim().toIntOrNull()?.takeIf { it > 0 }
        ?: throw GradleException("FSCM_VERSION_CODE in local.properties must be a positive integer")
}
val appVersionName = localProperties.map { properties ->
    val configured = properties.getProperty("FSCM_VERSION_NAME") ?: return@map "0.1.3"
    configured.trim().takeIf { it.isNotBlank() }
        ?: throw GradleException("FSCM_VERSION_NAME in local.properties must not be blank")
}

fun releaseSigningValue(name: String) = providers.gradleProperty(name)
    .orElse(providers.environmentVariable(name))
    .orElse(localProperties.map { it.getProperty(name).orEmpty() })
    .orNull
    ?.trim()
    ?.removeSurrounding("\"")
    ?.removeSurrounding("'")
    ?.takeIf(String::isNotBlank)

val releaseStoreFile = releaseSigningValue("FSCM_RELEASE_STORE_FILE")
val releaseStorePassword = releaseSigningValue("FSCM_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = releaseSigningValue("FSCM_RELEASE_KEY_ALIAS")
val releaseKeyPassword = releaseSigningValue("FSCM_RELEASE_KEY_PASSWORD")
val hasReleaseSigning = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { it != null }
val prodReleaseRequested = gradle.startParameter.taskNames.any {
    it.contains("ProdRelease", ignoreCase = true)
}
if (prodReleaseRequested && !hasReleaseSigning) {
    throw GradleException(
        "prodRelease requires FSCM_RELEASE_STORE_FILE, FSCM_RELEASE_STORE_PASSWORD, " +
            "FSCM_RELEASE_KEY_ALIAS and FSCM_RELEASE_KEY_PASSWORD",
    )
}

android {
    buildFeatures {
        buildConfig = true
    }

    val fscmReleaseSigning = if (hasReleaseSigning) {
        signingConfigs.create("fscmRelease") {
            storeFile = file(checkNotNull(releaseStoreFile))
            storePassword = checkNotNull(releaseStorePassword)
            keyAlias = checkNotNull(releaseKeyAlias)
            keyPassword = checkNotNull(releaseKeyPassword)
        }
    } else {
        null
    }

    defaultConfig {
        applicationId = "com.fscm.app"
        versionCode = appVersionCode.get()
        versionName = appVersionName.get()

        // Custom test runner to set up Hilt dependency graph
        testInstrumentationRunner = "com.google.samples.apps.nowinandroid.core.testing.NiaTestRunner"

        ndk {
            abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64"))
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = NiaBuildType.DEBUG.applicationIdSuffix
        }
        release {
            isMinifyEnabled = providers.gradleProperty("minifyWithR8")
                .map(String::toBooleanStrict).getOrElse(true)
            applicationIdSuffix = NiaBuildType.RELEASE.applicationIdSuffix
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"),
                          "proguard-rules.pro")

            signingConfig = fscmReleaseSigning
        }
    }

    packaging {
        resources {
            excludes.add("/META-INF/{AL2.0,LGPL2.1}")
        }
    }
    lint {
        // FSCM intentionally wraps Material 3 directly instead of using the NIA business UI layer.
        disable += "DesignSystem"
    }
    testOptions.unitTests.isIncludeAndroidResources = true
    namespace = "com.google.samples.apps.nowinandroid"
}

androidComponents {
    onVariants { variant ->
        val flavorName = variant.productFlavors
            .firstOrNull { it.first == "contentType" }
            ?.second
            ?: "demo"
        val flavorBackendUrlKey = "BACKEND_URL_${flavorName.uppercase()}"
        val fallbackBackendUrl = when (flavorName) {
            "prod" -> "https://fscm.freeb.vip/api/"
            else -> "http://10.0.2.2:8088/api/"
        }
        val backendUrl = localProperties.map { properties ->
            val rawValue = properties.getProperty(flavorBackendUrlKey)?.trim()?.takeIf { it.isNotBlank() }
                ?: properties.getProperty("BACKEND_URL")?.trim()?.takeIf { it.isNotBlank() }
                ?: fallbackBackendUrl
            val normalized = "${rawValue.trim().trimEnd('/')}/"
            val escaped = normalized
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
            "\"$escaped\""
        }
        variant.buildConfigFields!!.put(
            "BACKEND_URL",
            backendUrl.map { value ->
                BuildConfigField(
                    type = "String",
                    value = value,
                    comment = null,
                )
            },
        )
    }
}

dependencies {
    implementation(projects.core.data)
    implementation(projects.core.designsystem)
    implementation(projects.core.ui)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.iconsExtended)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.compose.material3.adaptive)
    implementation(libs.androidx.compose.material3.adaptive.layout)
    implementation(libs.androidx.compose.material3.adaptive.navigation)
    implementation(libs.androidx.compose.material3.adaptive.navigation3)
    implementation(libs.androidx.compose.material3.windowSizeClass)
    implementation(libs.androidx.compose.runtime.tracing)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtimeCompose)
    implementation(libs.androidx.lifecycle.viewModelCompose)
    implementation(libs.androidx.hilt.lifecycle.viewModelCompose)
    implementation(libs.androidx.lifecycle.viewModel.navigation3)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.tracing.ktx)
    implementation(libs.androidx.window.core)
    implementation(libs.androidx.dataStore.preferences)
    implementation(libs.androidx.work.ktx)
    implementation(libs.hilt.ext.work)
    implementation(libs.room.ktx)
    implementation(libs.room.runtime)
    implementation(libs.coil.kt)
    implementation(libs.coil.kt.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.jose4j)
    implementation(libs.okhttp.logging)
    implementation(libs.retrofit.core)
    implementation(libs.retrofit.kotlin.serialization)
    implementation(libs.escpos.printer)
    implementation(libs.sqlcipher)

    ksp(libs.hilt.compiler)
    ksp(libs.hilt.ext.compiler)
    ksp(libs.room.compiler)

    debugImplementation(libs.androidx.compose.ui.testManifest)
    debugImplementation(projects.uiTestHiltManifest)

    kspTest(libs.hilt.compiler)

    testImplementation(libs.hilt.android.testing)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.androidx.work.testing)

    testDemoImplementation(libs.androidx.navigation.testing)
    testDemoImplementation(libs.robolectric)
    testDemoImplementation(libs.roborazzi)

    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.compose.ui.test)
    androidTestImplementation(libs.hilt.android.testing)
    androidTestImplementation(libs.kotlin.test)

    baselineProfile(projects.benchmarks)
}

baselineProfile {
    // Don't build on every iteration of a full assemble.
    // Instead enable generation directly for the release build variant.
    automaticGenerationDuringBuild = false

    variants {
        register("release") {
            automaticGenerationDuringBuild = false
        }
    }

    // Make use of Dex Layout Optimizations via Startup Profiles
    dexLayoutOptimization = true
}

dependencyGuard {
    configuration("prodReleaseRuntimeClasspath")
}
