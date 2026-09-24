val kotlin_version: String by extra
plugins {
    alias(libs.plugins.androidLibrary)
    kotlin("multiplatform")
}

kotlin {
    jvmToolchain(17)
    androidTarget {}

    jvm {
        testRuns["test"].executionTask.configure {
            useJUnitPlatform()
        }
    }

    js(IR) {
        browser {
            testTask {
                useKarma {
                    useChromeHeadless()
                }
            }
        }
        nodejs()
    }

    applyDefaultHierarchyTemplate()

    linuxX64("linuxX64")
    linuxArm64("linuxArm64")
    macosArm64()
    macosX64()

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
        val jvmMain by getting {}
        val jvmTest by getting

        val jsMain by getting {
            dependencies {
                implementation(libs.kotlinx.coroutines.js)
            }
        }

        val jsTest by getting
        val linuxX64Main by getting
        val linuxArm64Main by getting
        val nativeMain by getting

        val androidMain by getting {
            dependsOn(jvmMain)
            dependencies {
                implementation(libs.appcompat)
            }
        }
    }
}

android {
    compileSdk = 34
    namespace = "cz.sazel.hellokotlin.lib"
}
