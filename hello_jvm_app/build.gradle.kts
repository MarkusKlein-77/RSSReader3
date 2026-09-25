import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    kotlin("multiplatform")
}

kotlin {
    jvmToolchain(17)
    jvm {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        mainRun {
            mainClass = "cz.sazel.hellokotlin.MainKt"
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":hello_shared"))
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(project(":hello_shared"))
            }
        }
        val jvmMain by getting
        val jvmTest by getting
    }
}

tasks.register<Jar>("fatJar") {
    dependsOn(tasks.named("jvmJar"))
    archiveClassifier.set("fat")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes("Main-Class" to "cz.sazel.hellokotlin.MainKt")
    }

    from(kotlin.targets.getByName("jvm").compilations.getByName("main").output.allOutputs)
    from({
        configurations.getByName("jvmRuntimeClasspath").map { dependency ->
            if (dependency.isDirectory) dependency else zipTree(dependency)
        }
    })
}

