package com.example.hello.shared

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class BuildUpdateCheckerTest {
    @Test
    fun returnsNewerPlatformBuild() = runBlocking {
        val checker = checker(validManifest())
        val update = checker.checkForUpdate()

        assertEquals(1421L, update?.buildNumber)
        assertEquals("a".repeat(40), update?.commitSha)
        assertEquals(
            "https://markusklein-77.github.io/RSSReader/updates/builds/000000001421-${"a".repeat(12)}/RSSReader-android.apk",
            update?.assetUrl,
        )
        assertEquals("a".repeat(64), update?.assetSha256)
        checker.close()
    }

    @Test
    fun returnsNoUpdateWhenInstalledBuildIsCurrent() = runBlocking {
        val checker = checker(validManifest(), currentBuildNumber = 1421L)

        assertNull(checker.checkForUpdate())
        checker.close()
    }

    @Test
    fun rejectsUntrustedAssetUrl() = runBlocking {
        val manifest = validManifest().replace(
            "https://markusklein-77.github.io/RSSReader/updates/builds/000000001421-${"a".repeat(12)}/RSSReader-android.apk",
            "https://example.com/RSSReader-android.apk",
        )
        val checker = checker(manifest)

        val exception = try {
            checker.checkForUpdate()
            fail("Expected invalid package URL to be rejected.")
        } catch (exception: UpdateCheckException) {
            exception
        }
        assertTrue(exception.message.orEmpty().contains("package URL"))
        checker.close()
    }

    @Test
    fun rejectsUnsupportedPlatformAsset() = runBlocking {
        val manifest = validManifest().replace(
            "\"android\": {",
            "\"windows-x64\": {",
        )
        val checker = checker(manifest)

        val exception = try {
            checker.checkForUpdate()
            fail("Expected missing Android package to be rejected.")
        } catch (exception: UpdateCheckException) {
            exception
        }
        assertTrue(exception.message.orEmpty().contains("unsupported platform"))
        checker.close()
    }

    @Test
    fun rejectsMalformedAssetChecksum() = runBlocking {
        val checker = checker(validManifest().replace("a".repeat(64), "not-a-checksum"))

        val exception = try {
            checker.checkForUpdate()
            fail("Expected malformed checksum to be rejected.")
        } catch (exception: UpdateCheckException) {
            exception
        }
        assertTrue(exception.message.orEmpty().contains("checksum"))
        checker.close()
    }

    @Test
    fun reportsHttpFailure() = runBlocking {
        val client = HttpClient(
            MockEngine { respond("unavailable", status = HttpStatusCode.ServiceUnavailable) },
        ) {
            install(HttpTimeout)
        }
        val checker = BuildUpdateChecker(1, "android", client)

        val exception = try {
            checker.checkForUpdate()
            fail("Expected HTTP failure to be reported.")
        } catch (exception: UpdateCheckException) {
            exception
        }
        assertTrue(exception.message.orEmpty().contains("503"))
        checker.close()
    }

    private fun checker(
        manifest: String,
        currentBuildNumber: Long = 1L,
    ): BuildUpdateChecker {
        val client = HttpClient(MockEngine { respond(manifest) }) {
            install(HttpTimeout)
        }
        return BuildUpdateChecker(currentBuildNumber, "android", client)
    }

    private fun validManifest(): String =
        """
        {
          "schemaVersion": 1,
          "buildNumber": 1421,
          "runId": 203874,
          "commitSha": "${"a".repeat(40)}",
          "builtAt": "2026-11-21T17:00:00Z",
          "summary": "Build update",
          "assets": {
            "android": {
              "url": "https://markusklein-77.github.io/RSSReader/updates/builds/000000001421-${"a".repeat(12)}/RSSReader-android.apk",
              "sha256": "${"a".repeat(64)}"
            }
          }
        }
        """.trimIndent()
}
