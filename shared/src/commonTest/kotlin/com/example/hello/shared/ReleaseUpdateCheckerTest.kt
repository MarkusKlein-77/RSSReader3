package com.example.hello.shared

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ReleaseUpdateCheckerTest {
    @Test
    fun returnsNewerAndroidReleaseWithVerifiedMetadata() = runTest {
        val checker = checker(currentBuildNumber = 62_000)

        try {
            val update = checker.checkForUpdate()

            assertEquals("0.1.7", update?.versionName)
            assertEquals(63_001L, update?.buildNumber)
            assertEquals("a".repeat(40), update?.commitSha)
            assertEquals("Release notes for 0.1.7", update?.summary)
            assertEquals(APK_URL, update?.assetUrl)
            assertEquals("b".repeat(64), update?.assetSha256)
            assertEquals(RELEASE_PAGE_URL, update?.releasePageUrl)
        } finally {
            checker.close()
        }
    }

    @Test
    fun returnsNoUpdateWhenReleaseIsNotNewer() = runTest {
        val checker = checker(currentBuildNumber = 63_001)

        try {
            assertNull(checker.checkForUpdate())
        } finally {
            checker.close()
        }
    }

    @Test
    fun rejectsReleaseAssetsOutsideTheRepository() = runTest {
        val checker = checker(
            currentBuildNumber = 62_000,
            releaseBody = makeReleaseBody().replace(
                UPDATE_METADATA_URL,
                "https://example.com/RSSReader-android-update.json",
            ),
        )

        try {
            assertFailsWith<UpdateCheckException> { checker.checkForUpdate() }
        } finally {
            checker.close()
        }
    }

    @Test
    fun rejectsPrereleases() = runTest {
        val checker = checker(
            currentBuildNumber = 62_000,
            releaseBody = makeReleaseBody().replace("\"prerelease\": false", "\"prerelease\": true"),
        )

        try {
            assertFailsWith<UpdateCheckException> { checker.checkForUpdate() }
        } finally {
            checker.close()
        }
    }

    @Test
    fun rejectsMetadataForADifferentRelease() = runTest {
        val checker = checker(
            currentBuildNumber = 62_000,
            metadataBody = makeMetadataBody().replace("\"tagName\": \"v0.1.7\"", "\"tagName\": \"v0.1.6\""),
        )

        try {
            assertFailsWith<UpdateCheckException> { checker.checkForUpdate() }
        } finally {
            checker.close()
        }
    }

    @Test
    fun reportsGitHubHttpFailures() = runTest {
        val checker = ReleaseUpdateChecker(
            currentBuildNumber = 62_000,
            client = HttpClient(MockEngine { respond("unavailable", HttpStatusCode.ServiceUnavailable) }) {
                install(HttpTimeout)
            },
        )

        try {
            assertFailsWith<UpdateCheckException> { checker.checkForUpdate() }
        } finally {
            checker.close()
        }
    }
}

private const val LATEST_RELEASE_API_URL =
    "https://api.github.com/repos/MarkusKlein-77/RSSReader3/releases/latest"
private const val RELEASE_PAGE_URL =
    "https://github.com/MarkusKlein-77/RSSReader3/releases/tag/v0.1.7"
private const val APK_URL =
    "https://github.com/MarkusKlein-77/RSSReader3/releases/download/v0.1.7/RSSReader-android.apk"
private const val UPDATE_METADATA_URL =
    "https://github.com/MarkusKlein-77/RSSReader3/releases/download/v0.1.7/RSSReader-android-update.json"

private fun checker(
    currentBuildNumber: Long,
    releaseBody: String = makeReleaseBody(),
    metadataBody: String = makeMetadataBody(),
): ReleaseUpdateChecker {
    val client = HttpClient(
        MockEngine { request ->
            when (request.url.toString()) {
                LATEST_RELEASE_API_URL -> respond(releaseBody)
                UPDATE_METADATA_URL -> respond(metadataBody)
                else -> respond("not found", HttpStatusCode.NotFound)
            }
        },
    ) {
        install(HttpTimeout)
    }
    return ReleaseUpdateChecker(currentBuildNumber, client)
}

private fun makeReleaseBody(): String = """
    {
      "tag_name": "v0.1.7",
      "draft": false,
      "prerelease": false,
      "html_url": "$RELEASE_PAGE_URL",
      "body": "Release notes for 0.1.7",
      "assets": [
        {
          "name": "RSSReader-android.apk",
          "browser_download_url": "$APK_URL"
        },
        {
          "name": "RSSReader-android-update.json",
          "browser_download_url": "$UPDATE_METADATA_URL"
        }
      ]
    }
""".trimIndent()

private fun makeMetadataBody(): String = """
    {
      "schemaVersion": 1,
      "tagName": "v0.1.7",
      "versionName": "0.1.7",
      "versionCode": 63001,
      "commitSha": "${"a".repeat(40)}",
      "builtAt": "2026-10-12T12:00:00Z",
      "apkUrl": "$APK_URL",
      "apkSha256": "${"b".repeat(64)}"
    }
""".trimIndent()
