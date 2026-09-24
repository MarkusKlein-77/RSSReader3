# Compile plan for Android, JVM, and browser targets

This repository is configured for three main build targets:

- Android app: `hello_android_app`
- JVM app: `hello_jvm_app`
- Browser app: `hello_js_browser_app`

The iOS app and SKIE setup were removed from the project, so the shared library no longer targets iOS.

## Current environment status

Checked on 2026-09-24 in this workspace:

- JDK 17: already installed and working (`Temurin OpenJDK 17.0.20.1`)
- Gradle wrapper: available via `./gradlew` / `./gradlew.bat`
- Android SDK: not currently detected on PATH / not installed in this environment
- Android debug tools (`adb`): not available
- Node.js / npm: not installed in this environment
- Chrome / Chromium for Karma: not installed in this environment

## Install requirements by target

### 1) Android app

Required:

- JDK 17
- Android Studio or Android SDK command-line tools
- Android SDK Platform 34+ (the app is configured with `compileSdk = 36`)
- Android SDK Build-Tools and Platform-Tools
- `ANDROID_HOME` or `local.properties` pointing to the SDK path

Recommended commands:

```bash
./gradlew hello_android_app:build
# or
./gradlew hello_android_app:assembleDebug
```

If `local.properties` is missing, create it with a valid SDK path, such as:

```properties
sdk.dir=C\:/Users/<you>/AppData/Local/Android/Sdk
```

### 2) JVM app

Required:

- JDK 17
- Gradle wrapper (already in the repository)

Recommended command:

```bash
./gradlew hello_jvm_app:build
```

This is the simplest target to build in a standard environment because it does not require Android SDK or Node.js.

### 3) Browser app

Required:

- JDK 17
- Node.js and npm
- Chrome or Chromium available for Karma headless test execution

Recommended command:

```bash
./gradlew hello_js_browser_app:build
```

The Kotlin/JS Gradle configuration uses `useChromeHeadless()`, so a browser runtime must be available during the test/build phase.

## Minimum practical setup

For a developer machine that should compile all three targets, the minimum setup is:

- JDK 17
- Android Studio with SDK installation
- Node.js + npm
- Chrome / Chromium
- Git configured with the repository remote

## Immediate next steps

1. Install Android SDK + Android Studio if Android builds are required.
2. Install Node.js + npm if the browser build is required.
3. Ensure Chrome/Chromium is available for the JS browser test task.
4. Run these commands locally to verify the setup:

```bash
./gradlew hello_jvm_app:build
./gradlew hello_js_browser_app:build
./gradlew hello_android_app:build
```

This environment already has Java 17, so the JVM target is the first one that can be compiled immediately. The Android and browser targets still need missing tooling installed.
