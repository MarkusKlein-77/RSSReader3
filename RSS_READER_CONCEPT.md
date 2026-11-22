# RSS Reader Concept

## Goal

This project is a Kotlin Multiplatform RSS reader that uses a user-provided Miniflux server as its article source. Android, JVM desktop, and browser WebAssembly builds share the Compose UI and application logic. The current delivery covers connection setup, persistent configuration, and article display; later product requirements can extend this foundation.

## Mandatory Basic Rule: Verify Builds Through GitHub Actions

Do not run local builds or tests. Commit and push the changes, then use the repository's GitHub Actions workflows as the required build and test verification. Check the workflow run and its jobs after pushing, and report their actual results. If a workflow fails, diagnose and fix the cause, then push and check the next run. Never substitute a local build for this CI verification.

## Repository Starting Point

- `shared` contains the Miniflux client, shared application state, platform abstractions, and Compose UI. It targets Android, desktop JVM, and `wasmJs` and exposes the `App()` composable used by all three launchers.
- `androidApp`, `desktopApp`, and `webApp` are platform launchers. Keep platform-specific setup in these modules or their source sets, not in the common UI.
- `.github/workflows/build-platform-artifacts.yml` builds Android, JVM desktop, and Wasm artifacts. Tagged builds publish signed Android and Windows packages as GitHub Release assets; Pages deploys the Wasm app only.

## Proposed Module Responsibilities

| Area | Responsibility |
| --- | --- |
| `shared/commonMain` | Article/configuration models, Miniflux API adapter, shared application state, Compose screens, and user actions. |
| `shared` platform source sets | Select the HTTP client engine and implement platform settings/secret storage and article opening. Keep browser APIs out of Android/JVM code and vice versa. |
| `androidApp`, `desktopApp`, `webApp` | Construct or obtain platform dependencies and start the shared `App()`. Preserve the existing thin-launcher pattern. |

The app uses Ktor Client and JSON serialization in common code, with an HTTP engine selected per target. Keep dependency versions in `gradle/libs.versions.toml`.

## General Responsive Layout Concept

Shared UI should make use of available space on larger browser and desktop displays instead of keeping article tiles sized for a narrow viewport. On very wide screens, display no more than eight tiles in a row. As tile widths grow, preserve the established tile aspect ratio so the cards scale proportionally rather than stretching. Layouts should still adapt naturally to smaller screens and remain implemented in shared Compose UI.

## Step-by-Step Delivery

### 1. Preserve the runnable baseline

Use CI/CD as the primary place to run the build gates: `:shared:desktopTest`, `:androidApp:assembleDebug`, `:desktopApp:createDistributable`, and `:webApp:wasmJsBrowserDistribution`. Run these locally only when the required toolchain is already available; keep each platform launcher calling the same shared `App()`.

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

Before requesting a server-side feed refresh, fetch `/v1/feeds` and inspect the feeds' `checked_at` timestamps. Use the most recent valid timestamp across the feeds: if it is less than 10 minutes old, skip the server refresh and load articles once without the delayed follow-up. If no valid timestamp is available or the latest check is at least 10 minutes old, request a server refresh, load articles after the existing 5-second wait, then perform the existing second article load after 30 seconds.

Test the adapter using a mocked HTTP engine, including successful authentication, unauthorized credentials, server errors, and article parsing. Avoid requiring a production Miniflux instance in CI.

**Done when:** repository tests exercise requests and response mapping without network access.

### 4. Add platform settings and secret handling

Keep the settings interface common and implement it per target. Save the normalized URL and token only after connection validation succeeds. Load saved settings at startup and allow them to be replaced or cleared from the configuration screen.

- **Android:** use app-private storage for the URL and a platform-protected secret store for the token (Android Keystore-backed encryption); do not store the token in plain preferences.
- **JVM desktop:** use the operating system credential store for the token where available, with the server URL in normal application preferences. Document the behavior if no supported secret store exists rather than silently claiming encrypted storage.
- **Wasm browser:** save the URL and token in persistent, host-only cookies so configuration survives browser restarts. These cookies are JavaScript-readable and are not a secure secret vault; disclose this limitation and provide a clear disconnect/forget action. Do not enable cross-site cookies as a workaround for API CORS.

**Done when:** configuration survives an app restart on each target and can be deleted; platform-specific storage tests or manual checks cover each implementation.

### 5. Build the shared configuration and article UI

Maintain the shared app state and Compose screens in `shared/commonMain`:

- On first start, show a compact setup form for the Miniflux server URL and access token.
- When already configured, show articles and provide a configuration icon in the top app bar.
- Validate the server and credentials before saving; show connection progress and actionable errors without exposing the token.
- Display article title, feed, publication date, and available summary/content. Convert publication timestamps to the viewer's local system timezone for display without changing the source timestamp. Include loading, empty, and retry states.
- Keep layout and interaction usable at phone, desktop, and browser sizes. Keep keyboard and screen-reader access in view when implementing fields and icon buttons.

Keep UI state transitions and rendering in shared code. Do not add platform-specific duplicate screens.

**Done when:** the same setup and article-list flow runs from all three launchers.

### 6. Verify browser deployment and server compatibility

The Wasm app is hosted as a static site under the Pages `/RSSReader` path, while the Miniflux instance will usually have a different origin. Browser requests therefore require the Miniflux host to allow the deployed app origin through CORS, including the `X-Auth-Token` header and preflight `OPTIONS` requests. The Miniflux URL must also be reachable from the user's browser; HTTPS hosting cannot call an insecure HTTP API because browsers block mixed content. Show these constraints as useful connection errors and document the required server-side CORS configuration.

Test the deployed subpath, asset URLs, configuration persistence, and a real browser-to-Miniflux connection against a controlled server. Never add a shared server token or user's credentials to GitHub Actions secrets for this client-side app.

**Done when:** the production Wasm artifact loads from the existing Pages path and a browser can connect to a CORS-configured Miniflux host.

### 7. Extend CI/CD as a merge gate

Keep the existing independent Android, JVM, and Wasm artifact jobs and their artifact outputs. Add a test job (or test steps before packaging) for `:shared:desktopTest` to exercise common tests on the JVM; keep the platform build jobs as checks for target-specific compilation. Ensure pull requests build all three platforms without deployment, and retain the existing rule that Pages deployment runs only for non-PR events. The Pages deployment needs its existing repository token only in the deployment job.

**Done when:** a pull request proves common tests and all three platform builds pass, and a push deploys the Wasm distribution only after the Wasm build succeeds.

## Android Signing and Distribution Concept (reviewed 2026-10-06)

### Signing requirements

Android requires every installable APK to be signed, and an installed app can only be updated by an APK signed with the same app-signing certificate (or a supported signing-key rotation lineage). The application ID is currently `de.onkelholle.RSSReader`. Treat it and the signing identity as persistent app identity. Do not change either during routine refactoring or key-secret updates.

### Recommended signing model

Keep direct APK distribution outside Google Play; it does not require a Play Console account. Use one dedicated, long-lived signing keystore for this app and sign every tagged release offered as an in-place update:

1. Generate the signing key once and keep an encrypted offline backup in a separate secure location. Record the key alias, app ID, and signing certificate SHA-256 fingerprint in private project records. The repository ignores `.jks`, `.keystore`, and `.p12` files; never commit the keystore or its passwords.
2. Add `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD` as GitHub Actions repository secrets. Store the certificate fingerprint in the `ANDROID_SIGNING_CERT_SHA256` Actions variable. Restrict who can create and push release tags that publish signed packages.
3. Every workflow that produces an APK intended to update an installed app must use the same persistent keystore. Gradle must fail that distributable build if any signing value is missing. A CI debug APK signed with a runner-generated debug key is not a valid update for an installation signed by another key.
4. Before making a release APK available to the updater, the workflow verifies it using Android SDK `apksigner` and checks that its signer certificate SHA-256 matches `ANDROID_SIGNING_CERT_SHA256`. Preserve an increasing `versionCode` for every updater-distributed release.
5. Test updates using actual release artifacts: install one signed release, then install a newer release APK with `adb install -r`. Confirm that data remains and the package updates without an uninstall. A debug APK is not a valid substitute for this update test.

To obtain the fingerprint locally without uploading the keystore, run `keytool -list -v -keystore <path-to-p12> -storetype PKCS12 -alias <key-alias>` and use the certificate's SHA-256 value (colons are accepted) as the `ANDROID_SIGNING_CERT_SHA256` Actions variable. Never put the fingerprint in a secret-bearing command line; it is public certificate data.

Loss of the signing key can permanently prevent updates to existing installations. Do not rotate or replace it casually. If rotation ever becomes necessary, use Android’s supported signing-key rotation and proof-of-rotation tooling, verify device/API compatibility, and test upgrades from prior builds before distributing the new APK. Play App Signing is a separate distribution option: it requires a Play Console account and is not needed for the current direct-APK distribution concept.

### Android developer verification and the account question

An Android account is **not required to generate a cryptographically signed APK or to publish one on GitHub today**. That is separate from Android’s new developer-verification rules. According to the [Android developer-verification guide](https://developer.android.com/developer-verification/guides), verification enforcement began on September 30, 2026 in Brazil, Indonesia, Singapore, and Thailand, with global expansion on certified Android devices planned for 2027. The guide says developers distributing outside Google Play should sign up for an Android Developer Console account, verify their identity, and register package names using an APK signed with the app’s private key.

The guide also describes account-avoiding alternatives, with tradeoffs:

- **Limited distribution:** no identity verification, but distribution is limited to up to 20 invited devices. This fits personal testing, not broad distribution.
- **Unverified sideloading:** no developer account, but users may have to use Android’s advanced sideloading flow and accept additional safeguards. This is a higher-friction fallback, not a dependable consumer update experience.
- **Verified external distribution:** intended for wider distribution outside Play. It involves Android Developer Console registration and identity verification, but does not require publishing in the Play Store or creating a Play Console listing.

Keep the signing design independent of any Google account. The update concept below checks published GitHub Releases; Android's account and sideloading requirements still apply to every APK distributed this way. Before the 2027 expansion, decide whether to accept the extra unverified-install flow or register through Android Developer Console for the normal install/update experience. Reuse the same app ID and signing key for registration; do not create a second “verified” build identity. Recheck the official rollout and account requirements before implementing registration, since the policy and console procedures may change.

**Done when:** every Android release offered as an update is signed by the stable app key or fails before publication; an in-place update from one such release to the next is verified; and the distribution choice for Android developer verification is explicitly made before global enforcement affects the target audience.

## GitHub Release Update Check and User-Approved Update Concept

### Goal and build source

Notify users when a newer Android version is available in the repository's latest published GitHub Release and let them start updating after explicitly accepting an in-app prompt. Only published, non-prerelease releases are eligible. Checking for an update must never install or replace the app silently.

Each tagged release publishes the signed Android APK and an `RSSReader-android-update.json` asset containing a schema version, tag and app version, Android `versionCode`, commit SHA, build time, exact APK URL, and SHA-256 digest. The release tag determines the user-facing `versionName`; the CI run number and attempt determine the monotonically increasing Android `versionCode`.

Normal branch builds do not publish Android update packages; they can still produce CI debug artifacts. GitHub Pages is used for the WebAssembly app only. GitHub Release assets are public and can be retrieved without a GitHub token. The Windows desktop updater is explicitly deferred.

### Build discovery and versioning

- Fetch GitHub's `/repos/MarkusKlein-77/RSSReader3/releases/latest` endpoint over HTTPS without sending a GitHub or Miniflux credential. Validate the latest tag, release page, APK asset, and metadata asset before considering an update. Reject prereleases, malformed metadata, or URLs that do not match the repository's release assets.
- Compare the metadata's Android `versionCode` with the installed `versionCode`, not the tag's semantic version: normal CI builds may have higher version codes than the currently published release, and must never be downgraded to. Offer only a release whose code is greater than the installed app's.
- Generate and attach complete update metadata only after the signed Android release APK has been built and verified. Keep the release's `versionName`, metadata, and APK version codes aligned.
- Check once after startup without blocking the app, and provide a manual **Check for updates** action in settings. Avoid repeated checks in one session and use a modest timeout.
- Display the installed Android release `versionName` immediately above the manual update-check button.
- Show the available release version, build number, short commit SHA, build time, and release notes, with **Update**, **Later**, and a link to the release. Do not download until the user chooses **Update**. Remembering a dismissed release is optional; it must not suppress a manual check.

### Platform update flow

- **Android:** After acceptance, download the APK to app-private storage and hand it to Android's package installer using a `content://` URI from a `FileProvider`. The user must approve Android's installer confirmation, and may need to allow installs from this app in system settings. Request unknown-source install permission only when required. Android's installer must enforce the existing package ID and signing-certificate continuity; never uninstall first or attempt a silent install. Preserve app data through the normal in-place package update.
- **Windows desktop:** Automatic updates are disabled for now. Continue using the existing desktop build artifact and manual distribution until a public package host that supports its file size is selected.
- **WebAssembly:** There is no client-side installer or separate update package. Successful builds can be deployed to GitHub Pages; the browser obtains them through its normal page reload/update behavior. Browser cache behavior and any future service worker must be considered before claiming the user is running the newest build.

### Integrity, privacy, and failure behavior

- Use only HTTPS release and package URLs from the configured repository. Verify the APK's SHA-256 digest before handing it to the installer or extracting it. The checksum detects corruption but is not independent publisher authentication; the Android package installer must still validate the APK signature and app identity.
- On Android, rely on the package installer to reject APKs with a mismatched app identity or signing key, consistent with the signing requirements above. Do not weaken signature checks to make an update succeed. Every release offered to users must use the persistent app signing key, not an ephemeral CI debug key.
- Keep the update checker independent of Miniflux configuration. Send no Miniflux URL, access token, or article data to GitHub. Do not execute downloaded files or extract archives before the user approves the update and the asset has passed validation.
- Make network errors, invalid release metadata/version codes, missing assets, digest failures, insufficient storage/permissions, and installation failures visible and actionable. Leave the currently installed app usable when an update fails; provide a manual release-page link as a fallback.

### Delivery and verification

Implement GitHub Release lookup and metadata validation behind a testable update-check abstraction, with the Android download/installer handler. Test newer and current version codes, malformed or mismatched metadata, missing assets, unexpected URLs, and network failures. For Android, verify consecutive releases signed with the same key update a prior installation in place and that Android still presents its installer confirmation. Verify the WebAssembly deployment refreshes normally after successful builds.

**Done when:** each eligible published Android release is discoverable with validated metadata; Android users are offered the latest newer release and its notes; no download or installation begins before in-app approval; the operating system retains final control of Android installation; and failed checks or updates do not damage the installed app.

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
- `loadArticles` follows Miniflux's paginated `offset`/`limit` results so older articles beyond the first API page are not omitted.
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

## CI Warning Prevention Concept (reviewed 2026-10-01)

### Observed GitHub Actions annotations

The latest successful workflow run, [Build platform artifacts #40](https://github.com/MarkusKlein-77/RSSReader3/actions/runs/36587492530), reported four warnings and three notices:

- Three artifact uploads (`android-app`, `wasm-web-app`, and `windows-desktop-app`) use `actions/upload-artifact@v4`; one artifact download uses `actions/download-artifact@v5`. GitHub reports that these action versions target the deprecated Node.js 20 runtime and are currently being forced onto Node.js 24.
- The Android build, Wasm build, and Pages deployment use `ubuntu-latest`. GitHub reports that this label will migrate to Ubuntu 26 during October-November 2026.
- The preceding tagged run, [Build platform artifacts #39](https://github.com/MarkusKlein-77/RSSReader3/actions/runs/36586398381), also failed in the Android release job. Its annotation only says `Process completed with exit code 1`; the warning summary does not identify the cause. Diagnose that failure from the job log independently of the persistent runner/action notices.

These are GitHub Actions runtime and runner-image annotations, not Kotlin or Gradle compiler warnings. The Actions annotation summary alone does not establish whether the build logs contain compiler warnings.

### Proposed changes

1. **Keep artifact actions on Node 24.** The urgent action-reference update is applied in the workflow: upload steps use `actions/upload-artifact@v7` and downloads use `actions/download-artifact@v8`. Both versions declare `runs.using: node24`; the latest releases at review time are [upload-artifact v7.0.1](https://github.com/actions/upload-artifact/releases/latest) and [download-artifact v8.0.1](https://github.com/actions/download-artifact/releases/latest). Preserve the artifact names and paths, then verify a complete run, including the tagged release artifact hand-off.
2. **Make the production runner baseline explicit.** Change the three Linux jobs from `ubuntu-latest` to `ubuntu-24.04` so their image does not change implicitly and the migration notice is removed. Keep the Windows job unchanged; it did not produce this notice.
3. **Test the next runner deliberately.** Add a scheduled or manual, non-publishing compatibility job on `ubuntu-26.04` for the Android and Wasm build tasks. Promote the main Linux jobs only after that check passes and the SDK/toolchain differences are understood. The Pages deployment should continue to consume the already-built Wasm artifact rather than repeat compilation.
4. **Keep action versions current with Renovate.** Configure the Renovate GitHub App for this repository and enable its GitHub Actions manager for workflow action and runner updates. Require the existing build checks on Renovate pull requests. If the project adopts full-SHA action pinning, enable Renovate's digest pinning so the tag and resolved commit stay updated together.

### Completion criteria

- A new successful run has no Node.js 20 deprecation warnings, including artifact upload and download steps.
- The production jobs no longer emit the `ubuntu-latest` migration notice.
- Android, desktop, and Wasm artifacts are still produced and handed off correctly; pull requests still do not deploy Pages.
- The Ubuntu 26 compatibility job passes before the production runner baseline is moved.

## First-Slice Acceptance Checklist

- One shared Compose UI is used by Android, JVM desktop, and Wasm.
- First launch asks for a Miniflux URL and access token; returning users can edit or forget them from the top-bar configuration action.
- Credentials are validated before being saved and are sent only in the `X-Auth-Token` header.
- A successful connection displays a bounded list of articles; loading, empty, authentication, network, and server-error states are distinguishable.
- Before a server refresh, recent feed `checked_at` timestamps (under 10 minutes old) suppress the server refresh and 30-second follow-up article load; otherwise the existing refresh-and-two-load sequence runs.
- The app makes no assumption that the Wasm browser can bypass Miniflux CORS, mixed-content rules, or browser storage security limits.
- Unit/adapter tests and the CI builds cover Android APK, JVM desktop distribution, and Wasm browser distribution; PRs do not deploy.

## Deliberately Deferred

Article read/unread mutations, feeds/categories, pagination controls, search, offline caching, multiple server profiles, notifications, and account management are outside this first slice until the follow-up requirements are defined.