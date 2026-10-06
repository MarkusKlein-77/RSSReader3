plugins {
    alias(libs.plugins.androidApplication)
    id("org.jetbrains.kotlin.android")
    alias(libs.plugins.composeCompiler)
}

android {
    namespace = "com.example.hello.android"
    compileSdk = 36

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    val releaseKeystoreFile = System.getenv("ANDROID_KEYSTORE_FILE")
    val releaseKeystorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
    val releaseKeyAlias = System.getenv("ANDROID_KEY_ALIAS")
    val releaseKeyPassword = System.getenv("ANDROID_KEY_PASSWORD")
    val hasReleaseSigning = listOf(
        releaseKeystoreFile,
        releaseKeystorePassword,
        releaseKeyAlias,
        releaseKeyPassword,
    ).all { !it.isNullOrBlank() }

    if (hasReleaseSigning) {
        signingConfigs {
            create("release") {
                storeFile = file(requireNotNull(releaseKeystoreFile))
                storePassword = requireNotNull(releaseKeystorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
            }
        }
    }

    gradle.taskGraph.whenReady {
        val releasePackagingTasks = allTasks.filter { task ->
            task.name.contains("Release") &&
                (task.name.startsWith("assemble") ||
                    task.name.startsWith("package") ||
                    task.name.startsWith("bundle"))
        }
        check(releasePackagingTasks.isEmpty() || hasReleaseSigning) {
            "Release packaging requires ANDROID_KEYSTORE_FILE, ANDROID_KEYSTORE_PASSWORD, " +
                "ANDROID_KEY_ALIAS, and ANDROID_KEY_PASSWORD."
        }
    }

    defaultConfig {
        applicationId = "de.onkelholle.RSSReader"
        minSdk = 24
        targetSdk = 36
        versionCode = 5
        versionName = "0.1.4"
    }

    buildTypes {
        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
}