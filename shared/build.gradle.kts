@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    androidTarget()
    jvm("desktop")
    wasmJs {
        browser()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":sharedLogic"))
            implementation(libs.ktor.client.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kamel.image.default)
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.ui)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.ktor.client.cio)
            }
        }
        androidMain.dependencies {
            implementation(libs.androidx.security.crypto)
            implementation(libs.ktor.client.android)
        }
        wasmJsMain.dependencies {
            implementation(libs.kotlinx.browser)
            implementation(libs.ktor.client.js)
        }
    }
}

android {
    namespace = "com.example.hello.shared"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
    }
}