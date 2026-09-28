# RSS Reader Concept

## Goal

Evolve this repository's Kotlin Multiplatform Hello World into an RSS reader that uses a Miniflux server as its article source. Android, JVM desktop, and browser WebAssembly builds should use the same Compose UI and shared application/domain code. The first delivery covers connection setup, persistent configuration, and displaying articles; later product requirements can extend this foundation.

## Repository Starting Point

- `shared` is the Compose Multiplatform UI module. It already targets Android, desktop JVM, and `wasmJs` and exposes the `App()` composable used by all three launchers.
- `sharedLogic` is a multiplatform library for common business logic and tests. It currently has Android, JVM, JS, and Wasm targets; it can own the Miniflux domain and use-case contracts.
- `androidApp`, `desktopApp`, and `webApp` are platform launchers. Keep platform-specific setup in these modules or their source sets, not in the common UI.
- `.github/workflows/build-platform-artifacts.yml` builds an Android APK, a JVM desktop distribution, and a Wasm browser distribution. On non-PR events it publishes the Wasm files to the `RSSReader` directory in the Pages repository.

## Proposed Module Responsibilities

| Area | Responsibility |
| --- | --- |
| `sharedLogic/commonMain` | Article/configuration models, Miniflux repository and API adapter, configuration validation rules, and load-articles use case/state. No Compose or platform APIs. |
| `sharedLogic` platform source sets | Select the HTTP client engine and implement platform settings/secret storage, exposed through a small platform factory. Keep browser APIs out of Android/JVM code and vice versa. |
| `shared/commonMain` | Shared Compose screens, navigation between setup/list views, UI state rendering, and user actions. Connect to shared logic through an injected app-level dependency. |
| `androidApp`, `desktopApp`, `webApp` | Construct or obtain platform dependencies and start the shared `App()`. Preserve the existing thin-launcher pattern. |

Prefer a shared HTTP abstraction and JSON serialization in common code, with an HTTP engine selected per target. Ktor Client is a suitable candidate because it has Android/JVM and browser Wasm support; confirm the exact engine and library versions against the repository's Kotlin and Compose versions before adding dependencies. Keep dependency versions in `gradle/libs.versions.toml`.

## Step-by-Step Delivery

### 1. Preserve the runnable baseline

Use CI/CD as the primary place to run the build gates: `:shared:allTests`, `:sharedLogic:allTests`, `:androidApp:assembleDebug`, `:desktopApp:createDistributable`, and `:webApp:wasmJsBrowserDistribution`. Run these locally only when the required toolchain is already available; keep each platform launcher calling the same shared `App()`.

**Done when:** the baseline builds on a clean checkout and the three launchers still point to the shared UI.

### 2. Define the first-slice domain and states

In `sharedLogic/commonMain`, add only the concepts needed for connection and article display:

- `MinifluxConfiguration`: normalized server URL and access token.
- `Article`: stable ID, title, feed name, publication time, and available summary/content fields.
- Repository operations to validate a connection and load a page of articles.
- Explicit result/UI states for unconfigured, validating, loading, loaded (including empty), and recoverable error cases.

Keep network DTOs separate from UI/domain models so API shape changes do not leak into Compose. Add common tests for URL normalization, state transitions, and mapping representative API responses.

**Done when:** the shared logic can be tested without a live Miniflux server or a platform runtime.

### 3. Add the Miniflux API adapter

Implement the repository with authenticated JSON requests. Send the access token in Miniflux's `X-Auth-Token` request header; never put it in a URL, log, crash report, or CI output. Use the server's `/v1/me` endpoint to validate saved/new credentials and `/v1/entries` to fetch the initial article page, starting with unread entries and a bounded page size. Handle HTTP errors, malformed responses, timeouts, and empty results as distinct, user-visible outcomes.

Test the adapter using a mocked HTTP engine, including successful authentication, unauthorized credentials, server errors, and article parsing. Avoid requiring a production Miniflux instance in CI.

**Done when:** repository tests exercise requests and response mapping without network access.

### 4. Add platform settings and secret handling

Put a common settings interface in the shared logic boundary and implement it per target. Save the normalized URL and token only after connection validation succeeds. Load saved settings at startup and allow them to be replaced or cleared from the configuration screen.

- **Android:** use app-private storage for the URL and a platform-protected secret store for the token (Android Keystore-backed encryption); do not store the token in plain preferences.
- **JVM desktop:** use the operating system credential store for the token where available, with the server URL in normal application preferences. Document the behavior if no supported secret store exists rather than silently claiming encrypted storage.
- **Wasm browser:** use origin-scoped browser storage for the URL and token. A JavaScript-readable cookie or `localStorage` is not a secure secret vault and is exposed to same-origin script/XSS; disclose this limitation and provide a clear disconnect/forget action. Do not enable cross-site cookies as a workaround for API CORS.

**Done when:** configuration survives an app restart on each target and can be deleted; platform-specific storage tests or manual checks cover each implementation.

### 5. Build the shared configuration and article UI

Replace the Hello World content in `shared/commonMain` with a shared app state and Compose screens:

- On first start, show a compact setup form for the Miniflux server URL and access token.
- When already configured, show articles and provide a configuration icon in the top app bar.
- Validate the server and credentials before saving; show connection progress and actionable errors without exposing the token.
- Display article title, feed, date, and available summary/content. Include loading, empty, and retry states.
- Keep layout and interaction usable at phone, desktop, and browser sizes. Keep keyboard and screen-reader access in view when implementing fields and icon buttons.

Keep UI state transitions in shared logic and rendering in Compose. Do not add platform-specific duplicate screens.

**Done when:** the same setup and article-list flow runs from all three launchers.

### 6. Verify browser deployment and server compatibility

The Wasm app is hosted as a static site under the Pages `/RSSReader` path, while the Miniflux instance will usually have a different origin. Browser requests therefore require the Miniflux host to allow the deployed app origin through CORS, including the `X-Auth-Token` header and preflight `OPTIONS` requests. The Miniflux URL must also be reachable from the user's browser; HTTPS hosting cannot call an insecure HTTP API because browsers block mixed content. Show these constraints as useful connection errors and document the required server-side CORS configuration.

Test the deployed subpath, asset URLs, configuration persistence, and a real browser-to-Miniflux connection against a controlled server. Never add a shared server token or user's credentials to GitHub Actions secrets for this client-side app.

**Done when:** the production Wasm artifact loads from the existing Pages path and a browser can connect to a CORS-configured Miniflux host.

### 7. Extend CI/CD as a merge gate

Keep the existing independent Android, JVM, and Wasm artifact jobs and their artifact outputs. Add a test job (or test steps before packaging) for `:sharedLogic:allTests` and `:shared:allTests`; keep the platform build jobs as checks for target-specific compilation. Ensure pull requests build all three platforms without deployment, and retain the existing rule that Pages deployment runs only for non-PR events. The Pages deployment needs its existing repository token only in the deployment job.

**Done when:** a pull request proves common tests and all three platform builds pass, and a push deploys the Wasm distribution only after the Wasm build succeeds.

## Workflow Lessons Learned

- **Compile Android launchers explicitly.** Applying the Android application and Compose compiler plugins alone does not compile a Kotlin `MainActivity`. Apply `org.jetbrains.kotlin.android` in `androidApp`, and align Java source/target compatibility with Kotlin's JVM 17 target. A successful APK task is not sufficient proof that the manifest's activity class is inside the APK; launch it on a device or emulator.
- **Prefer CI builds in constrained environments.** CI uses Temurin 17 and is the primary build/verification path when local resources or tooling are limited. Android Studio is not installed in the current workstation environment because of insufficient resources. Run local Gradle builds only when a compatible JDK and Android SDK are already configured; after a toolchain-related failure, do not repeatedly retry the same local build. Check CI results instead.
- **Verify the Android device round trip.** Confirm `adb devices -l` reports the phone as `device`, install with `adb install -r` to retain app data, then start with `adb shell am start -W`. Do not treat `Status: ok` by itself as proof of a healthy launch: verify the app PID and resumed activity, and inspect recent `AndroidRuntime`/`FATAL EXCEPTION` logs.
- **Test the artifact that was just built.** After a failed or interrupted build, check the APK timestamp and build result before installing; a previous APK may still exist and can hide a packaging problem. Reinstall only after a successful build and use `-r` when preserving settings matters.
- **Give Wasm a real viewport.** `ComposeViewport(document.body!!)` needs the host page's `html` and `body` to have full width and height. Check the actual canvas bounds at desktop and phone sizes, not only Wasm compilation. After publishing, load the deployment in a fresh tab or with a cache-busting URL when an already-open page may still have stale HTML or assets.
- **Keep build and runtime gates distinct.** CI's Android APK build checks packaging, but not that the entry activity launches. Keep platform build gates and common tests, and add an emulator smoke test where CI infrastructure permits. For local device testing, record build success, install success, foreground activity, and crash-log status separately.

## First-Slice Acceptance Checklist

- One shared Compose UI is used by Android, JVM desktop, and Wasm.
- First launch asks for a Miniflux URL and access token; returning users can edit or forget them from the top-bar configuration action.
- Credentials are validated before being saved and are sent only in the `X-Auth-Token` header.
- A successful connection displays a bounded list of articles; loading, empty, authentication, network, and server-error states are distinguishable.
- The app makes no assumption that the Wasm browser can bypass Miniflux CORS, mixed-content rules, or browser storage security limits.
- Unit/adapter tests and the CI builds cover Android APK, JVM desktop distribution, and Wasm browser distribution; PRs do not deploy.

## Deliberately Deferred

Article read/unread mutations, feeds/categories, pagination controls, search, offline caching, multiple server profiles, notifications, and account management are outside this first slice until the follow-up requirements are defined.