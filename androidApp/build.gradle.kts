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
    val ciRunNumber = System.getenv("GITHUB_RUN_NUMBER")?.toLongOrNull()
    val ciRunAttempt = System.getenv("GITHUB_RUN_ATTEMPT")?.toLongOrNull() ?: 1L
    val buildNumber = ciRunNumber?.let { it * 1_000L + ciRunAttempt } ?: 6L
    val releaseVersionName = System.getenv("GITHUB_REF_NAME")
        ?.takeIf { it.matches(Regex("""^[vV]\d+\.\d+\.\d+$""")) }
        ?.drop(1)
        ?: "0.1.4"
    check(buildNumber in 1..2_100_000_000L) {
        "The generated Android build number is outside the supported versionCode range."
    }
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

    val requestedReleasePackaging = gradle.startParameter.taskNames.any { requestedTask ->
        val taskName = requestedTask.substringAfterLast(':')
        taskName.contains("Release") &&
            (taskName.startsWith("assemble") ||
                taskName.startsWith("package") ||
                taskName.startsWith("bundle"))
    }
    check(!requestedReleasePackaging || hasReleaseSigning) {
        "Release packaging requires ANDROID_KEYSTORE_FILE, ANDROID_KEYSTORE_PASSWORD, " +
            "ANDROID_KEY_ALIAS, and ANDROID_KEY_PASSWORD."
    }

    defaultConfig {
        applicationId = "de.onkelholle.RSSReader"
        minSdk = 24
        targetSdk = 36
        versionCode = buildNumber.toInt()
        versionName = releaseVersionName
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