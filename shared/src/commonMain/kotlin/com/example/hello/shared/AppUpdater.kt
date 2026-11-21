package com.example.hello.shared

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import kotlinx.datetime.Clock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

const val BUILD_UPDATE_MANIFEST_URL =
    "https://onkelholle.de/RSSReader/updates/latest-build.json"

private const val BUILD_ASSET_BASE_URL =
    "https://onkelholle.de/RSSReader/updates/builds/"
private const val BUILD_RUN_PAGE_BASE_URL =
    "https://github.com/MarkusKlein-77/RSSReader3/actions/runs/"

data class AppUpdate(
    val buildNumber: Long,
    val commitSha: String,
    val builtAt: String,
    val summary: String,
    val assetUrl: String,
    val assetSha256: String,
    val buildPageUrl: String,
)

interface AppUpdater {
    suspend fun checkForUpdate(): AppUpdate?
    suspend fun install(update: AppUpdate)
    fun close()
}

class BuildUpdateChecker(
    private val currentBuildNumber: Long,
    private val platformAssetName: String,
    private val client: HttpClient = HttpClient { install(HttpTimeout) },
) {
    suspend fun checkForUpdate(): AppUpdate? {
        val response = client.get(
            URLBuilder(BUILD_UPDATE_MANIFEST_URL).apply {
                parameters.append("cacheBust", Clock.System.now().toEpochMilliseconds().toString())
            }.build(),
        ) {
            timeout { requestTimeoutMillis = 12_000 }
            headers.append(HttpHeaders.CacheControl, "no-cache")
        }
        if (response.status.value != 200) {
            throw UpdateCheckException("Update check failed with HTTP ${response.status.value}.")
        }

        val body = response.bodyAsText()
        if (body.length > MAX_MANIFEST_LENGTH) {
            throw UpdateCheckException("The update manifest is too large to be valid.")
        }

        val manifest = try {
            parseBuildManifest(body)
        } catch (exception: SerializationException) {
            throw UpdateCheckException("The update manifest is not valid JSON.", exception)
        } catch (exception: IllegalArgumentException) {
            throw UpdateCheckException(exception.message ?: "The update manifest is invalid.", exception)
        }
        if (manifest.buildNumber <= currentBuildNumber) return null

        val assets = manifest.assets
        val asset = assets[platformAssetName]
            ?: throw UpdateCheckException("No update package is available for this platform.")
        return AppUpdate(
            buildNumber = manifest.buildNumber,
            commitSha = manifest.commitSha,
            builtAt = manifest.builtAt,
            summary = manifest.summary,
            assetUrl = asset.url,
            assetSha256 = asset.sha256,
            buildPageUrl = manifest.buildPageUrl,
        )
    }

    fun close() {
        client.close()
    }

    private fun parseBuildManifest(body: String): BuildManifest {
        val root = Json.parseToJsonElement(body) as? JsonObject
            ?: throw IllegalArgumentException("The update manifest must be a JSON object.")
        val schemaVersion = root["schemaVersion"]?.jsonPrimitive?.intOrNull
        require(schemaVersion == 1) { "This update manifest version is not supported." }

        val buildNumber = root["buildNumber"]?.jsonPrimitive?.longOrNull
        require(buildNumber != null && buildNumber > 0) { "The update manifest has an invalid build number." }
        val commitSha = root.string("commitSha")
        require(commitSha != null && SHA_PATTERN.matches(commitSha)) {
            "The update manifest has an invalid commit SHA."
        }
        val builtAt = root.string("builtAt")
        require(!builtAt.isNullOrBlank()) { "The update manifest has no build timestamp." }
        val summary = root.string("summary").orEmpty().ifBlank { "A newer build is available." }
        val runId = root["runId"]?.jsonPrimitive?.longOrNull
        require(runId != null && runId > 0) { "The update manifest has an invalid workflow run ID." }
        val assets = root["assets"]?.jsonObject
            ?: throw IllegalArgumentException("The update manifest has no platform assets.")

        val parsedAssets = assets.mapValues { (platform, value) ->
            val asset = value.jsonObject
            val fileName = when (platform) {
                "android" -> "RSSReader-android.apk"
                else -> throw IllegalArgumentException("The update manifest lists an unsupported platform.")
            }
            val url = asset.string("url")
            require(url != null && isAllowedAssetUrl(url, fileName, buildNumber, commitSha)) {
                "The update manifest contains an invalid package URL."
            }
            val sha256 = asset.string("sha256")
            require(sha256 != null && SHA256_PATTERN.matches(sha256)) {
                "The update manifest contains an invalid package checksum."
            }
            PlatformAsset(url, sha256.lowercase())
        }

        val buildPageUrl = "$BUILD_RUN_PAGE_BASE_URL$runId"
        return BuildManifest(
            buildNumber = buildNumber,
            commitSha = commitSha,
            builtAt = builtAt,
            summary = summary.take(MAX_SUMMARY_LENGTH),
            buildPageUrl = buildPageUrl,
            assets = parsedAssets,
        )
    }

    private fun isAllowedAssetUrl(
        url: String,
        fileName: String,
        buildNumber: Long,
        commitSha: String,
    ): Boolean {
        if (!url.startsWith(BUILD_ASSET_BASE_URL) || '\\' in url || '?' in url || '#' in url) {
            return false
        }
        val path = url.removePrefix(BUILD_ASSET_BASE_URL)
        val segments = path.split('/')
        val expectedBuildDirectory = "${buildNumber.toString().padStart(12, '0')}-${commitSha.take(12)}"
        return segments.size == 2 &&
            segments[0] == expectedBuildDirectory &&
            segments[1] == fileName
    }

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull

    private data class PlatformAsset(
        val url: String,
        val sha256: String,
    )

    private data class BuildManifest(
        val buildNumber: Long,
        val commitSha: String,
        val builtAt: String,
        val summary: String,
        val buildPageUrl: String,
        val assets: Map<String, PlatformAsset>,
    )

    private companion object {
        const val MAX_MANIFEST_LENGTH = 64 * 1024
        const val MAX_SUMMARY_LENGTH = 240
        val SHA_PATTERN = Regex("^[0-9a-fA-F]{40}$")
        val SHA256_PATTERN = Regex("^[0-9a-fA-F]{64}$")
    }
}

class UpdateCheckException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
