# RSS Reader Concept

## Goal

This project is a Kotlin Multiplatform RSS reader that uses a user-provided Miniflux server as its article source. Android, JVM desktop, and browser WebAssembly builds share the Compose UI and application logic. The current delivery covers connection setup, persistent configuration, and article display; later product requirements can extend this foundation.

## Repository Starting Point

- `shared` contains the Miniflux client, shared application state, platform abstractions, and Compose UI. It targets Android, desktop JVM, and `wasmJs` and exposes the `App()` composable used by all three launchers.
- `androidApp`, `desktopApp`, and `webApp` are platform launchers. Keep platform-specific setup in these modules or their source sets, not in the common UI.
- `.github/workflows/build-platform-artifacts.yml` builds an Android APK, a JVM desktop distribution, and a Wasm browser distribution. On non-PR events it publishes the Wasm files to the `RSSReader` directory in the Pages repository.

## Proposed Module Responsibilities

| Area | Responsibility |
| --- | --- |
| `shared/commonMain` | Article/configuration models, Miniflux API adapter, shared application state, Compose screens, and user actions. |
| `shared` platform source sets | Select the HTTP client engine and implement platform settings/secret storage and article opening. Keep browser APIs out of Android/JVM code and vice versa. |
| `androidApp`, `desktopApp`, `webApp` | Construct or obtain platform dependencies and start the shared `App()`. Preserve the existing thin-launcher pattern. |

The app uses Ktor Client and JSON serialization in common code, with an HTTP engine selected per target. Keep dependency versions in `gradle/libs.versions.toml`.

## Step-by-Step Delivery

### 1. Preserve the runnable baseline

Use CI/CD as the primary place to run the build gates: `:shared:allTests`, `:androidApp:assembleDebug`, `:desktopApp:createDistributable`, and `:webApp:wasmJsBrowserDistribution`. Run these locally only when the required toolchain is already available; keep each platform launcher calling the same shared `App()`.

**Done when:** the baseline builds on a clean checkout and the three launchers still point to the shared UI.

### 2. Define the first-slice domain and states

Keep the common code focused on the concepts needed for connection and article display:

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

Keep the settings interface common and implement it per target. Save the normalized URL and token only after connection validation succeeds. Load saved settings at startup and allow them to be replaced or cleared from the configuration screen.

- **Android:** use app-private storage for the URL and a platform-protected secret store for the token (Android Keystore-backed encryption); do not store the token in plain preferences.
- **JVM desktop:** use the operating system credential store for the token where available, with the server URL in normal application preferences. Document the behavior if no supported secret store exists rather than silently claiming encrypted storage.
- **Wasm browser:** use origin-scoped browser storage for the URL and token. A JavaScript-readable cookie or `localStorage` is not a secure secret vault and is exposed to same-origin script/XSS; disclose this limitation and provide a clear disconnect/forget action. Do not enable cross-site cookies as a workaround for API CORS.

**Done when:** configuration survives an app restart on each target and can be deleted; platform-specific storage tests or manual checks cover each implementation.

### 5. Build the shared configuration and article UI

Maintain the shared app state and Compose screens in `shared/commonMain`:

- On first start, show a compact setup form for the Miniflux server URL and access token.
- When already configured, show articles and provide a configuration icon in the top app bar.
- Validate the server and credentials before saving; show connection progress and actionable errors without exposing the token.
- Display article title, feed, date, and available summary/content. Include loading, empty, and retry states.
- Keep layout and interaction usable at phone, desktop, and browser sizes. Keep keyboard and screen-reader access in view when implementing fields and icon buttons.

Keep UI state transitions and rendering in shared code. Do not add platform-specific duplicate screens.

**Done when:** the same setup and article-list flow runs from all three launchers.

### 6. Verify browser deployment and server compatibility

The Wasm app is hosted as a static site under the Pages `/RSSReader` path, while the Miniflux instance will usually have a different origin. Browser requests therefore require the Miniflux host to allow the deployed app origin through CORS, including the `X-Auth-Token` header and preflight `OPTIONS` requests. The Miniflux URL must also be reachable from the user's browser; HTTPS hosting cannot call an insecure HTTP API because browsers block mixed content. Show these constraints as useful connection errors and document the required server-side CORS configuration.

Test the deployed subpath, asset URLs, configuration persistence, and a real browser-to-Miniflux connection against a controlled server. Never add a shared server token or user's credentials to GitHub Actions secrets for this client-side app.

**Done when:** the production Wasm artifact loads from the existing Pages path and a browser can connect to a CORS-configured Miniflux host.

### 7. Extend CI/CD as a merge gate

Keep the existing independent Android, JVM, and Wasm artifact jobs and their artifact outputs. Add a test job (or test steps before packaging) for `:shared:allTests`; keep the platform build jobs as checks for target-specific compilation. Ensure pull requests build all three platforms without deployment, and retain the existing rule that Pages deployment runs only for non-PR events. The Pages deployment needs its existing repository token only in the deployment job.

**Done when:** a pull request proves common tests and all three platform builds pass, and a push deploys the Wasm distribution only after the Wasm build succeeds.

## Article Read Management Concept (planned follow-up)

### Overview

This follow-up feature adds automatic read-state tracking to the unread article list already rendered by the shared `App()` screen. The entry point remains the same: the unread view is a `LazyVerticalGrid` fed by `articles` and backed by `MinifluxApi.loadArticles(...)` in the shared common code. The new behavior extends the current flow without changing the app's existing server configuration or article browsing model.

### Functional requirement

When the user scrolls downward in the unread list, each article that moves above the visible viewport is considered “read enough” once it has crossed a top-of-screen threshold. The threshold is intentionally forgiving: there must be a grace area of two article lines still visible at the top before a read-marking request is sent. This means that an item is not marked read while it is still within roughly two lines of the viewport boundary; it becomes eligible only after it has traveled further upward than that buffer.

In practical terms:

- The unread list uses a scroll state tied to the `LazyVerticalGrid`.
- For each visible item, the app computes whether its top edge has moved above the viewport top by more than the configured grace margin, expressed as roughly two displayed article rows.
- Once the margin is exceeded, the article is moved to a pending-read set and the Miniflux “mark as read” API call is triggered.
- After a successful server response, the article is removed from the local unread list and will no longer appear in the unread view.
- If the user scrolls back upward before the server call completes, the item remains in the grace buffer and is not marked read prematurely.
- When an item has been scrolled out of the visible area by more than the grace margin, it is treated as read and removed from the unread list even if it was only briefly visible earlier.

### Intended UX behavior

- Unread articles are displayed as the current list from `MinifluxApi.loadArticles(..., includeRead = false)`.
- The app should maintain a local “pending read” tracker so an item is not re-sent repeatedly while the user keeps scrolling through the same area.
- The article icon/button and list row should remain unchanged for this first implementation; the behavior is purely automatic and list-driven.
- If the Miniflux request fails, the article should remain in the local unread list and be retried later, rather than silently disappearing.
- If the user toggles back to the “All articles” view, the same read-tracking logic should not push read state changes for articles that are intentionally being displayed in read mode.

### Top-bar “mark all as read” action

A dedicated action is added to the top bar next to the refresh button. The user can trigger it from the unread list only when the app is configured and not actively refreshing.

Behavior:

- The action calls a new Miniflux API routine that marks every currently loaded unread article as read on the server.
- Once the server confirms success, the entire unread list is cleared locally and the view returns to the empty-state text for the unread list.
- The action should be safe to repeat: if the server rejects the request or the list is empty, it should display a clear error or no-op message without corrupting the UI state.
- This action is independent from the scroll-driven auto-read logic and should not require the user to click each article individually.

### Data flow and state model

The feature is implemented in the shared layer so it works across Android, desktop, and browser builds.

Potential additions to the app state:

- `pendingReadIds: Set<Long>` to avoid duplicate requests while a read mutation is in flight.
- `markingAllRead: Boolean` to disable the top-bar button while bulk processing is active.
- `lastScrollEvent` or a derived `readThreshold` calculation from the grid scroll state.
- `readErrorMessage` for failed individual or bulk read operations.

The API layer should add two shared methods in `MinifluxApi`:

- `markArticleRead(configuration, articleId)` for the single-article scroll-driven read action.
- `markAllArticlesRead(configuration, articleIds)` for the top-bar bulk action.

The request helper should remain centralized so all Miniflux calls continue to use the configured `X-Auth-Token` header and the same error handling pattern already used by `validate()` and `loadArticles()`.

### Suggested implementation approach

1. Add a `LazyGridState` to the unread list in `App.kt`.
2. Track the first visible row and the pixel distance from the top edge of the grid to the item’s top edge.
3. Compare that distance with a threshold corresponding to roughly two article rows, using the article tile height as the reference.
4. When an item is beyond the threshold and not already in `pendingReadIds`, enqueue it for a read mutation.
5. On success, remove the item from the local `articles` list immediately; on failure, leave the item in-place and show a non-blocking error message.
6. Add the top-bar mark-all action in the same row that already contains the refresh and settings actions.

### Acceptance criteria for this feature

- Scrolling an unread article upward beyond the grace threshold marks it read on the Miniflux server.
- The article disappears from the unread list immediately after a successful mutation.
- The two-line grace buffer prevents accidental marking of articles that are still near the top of the viewport.
- The top-bar icon marks all loaded unread articles as read and clears them locally.
- Failed requests do not silently remove articles from the unread list.
- The feature respects the current shared Compose architecture and does not duplicate platform code.

### Risk and edge cases

- Very short or very tall article cards can distort the row-height estimate; the app should avoid brittle pixel math and instead use a conservative threshold with a measurable buffer based on the actual grid item height.
- If the list is refreshed while items are pending read, those IDs must be reconciled so no duplicate requests remain stuck in-flight.
- On slow connections, the same article could be seen in the unread list for multiple scroll passes; the `pendingReadIds` guard prevents repeated calls.
- A user who reopens the app before the pending request is acknowledged should see a consistent server state and a clean local list after refresh.

## Workflow Lessons Learned

- **Compile Android launchers explicitly.** Applying the Android application and Compose compiler plugins alone does not compile a Kotlin `MainActivity`. Apply `org.jetbrains.kotlin.android` in `androidApp`, and align Java source/target compatibility with Kotlin's JVM 17 target. A successful APK task is not sufficient proof that the manifest's activity class is inside the APK; launch it on a device or emulator.
- **Prefer CI builds in constrained environments.** CI uses Temuren 17 and is the primary build/verification path when local resources or tooling are limited. Android Studio is not installed in the current workstation environment because of insufficient resources. Run local Gradle builds only when a compatible JDK and Android SDK are already configured; after a toolchain-related failure, do not repeatedly retry the same local build. Check CI results instead.
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