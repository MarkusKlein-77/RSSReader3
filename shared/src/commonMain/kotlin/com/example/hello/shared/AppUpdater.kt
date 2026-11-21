package com.example.hello.shared

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

private const val LATEST_RELEASE_API_URL =
    "https://api.github.com/repos/MarkusKlein-77/RSSReader3/releases/latest"
private const val RELEASE_DOWNLOAD_BASE_URL =
    "https://github.com/MarkusKlein-77/RSSReader3/releases/download/"
private const val RELEASE_PAGE_BASE_URL =
    "https://github.com/MarkusKlein-77/RSSReader3/releases/tag/"
private const val ANDROID_APK_NAME = "RSSReader-android.apk"
private const val ANDROID_UPDATE_METADATA_NAME = "RSSReader-android-update.json"

data class AppUpdate(
    val versionName: String,
    val buildNumber: Long,
    val commitSha: String,
    val builtAt: String,
    val summary: String,
    val assetUrl: String,
    val assetSha256: String,
    val releasePageUrl: String,
)

interface AppUpdater {
    suspend fun checkForUpdate(): AppUpdate?
    suspend fun install(update: AppUpdate)
    fun close()
}

class ReleaseUpdateChecker(
    private val currentBuildNumber: Long,
    private val client: HttpClient = HttpClient { install(HttpTimeout) },
) {
    suspend fun checkForUpdate(): AppUpdate? {
        val releaseBody = requestText(LATEST_RELEASE_API_URL, isLatestReleaseRequest = true)
        val release = try {
            parseRelease(releaseBody)
        } catch (exception: SerializationException) {
            throw UpdateCheckException("GitHub returned an invalid release response.", exception)
        } catch (exception: IllegalArgumentException) {
            throw UpdateCheckException(exception.message ?: "The latest release is invalid.", exception)
        }

        val metadataBody = requestText(release.updateMetadataUrl)
        val metadata = try {
            parseUpdateMetadata(metadataBody, release)
        } catch (exception: SerializationException) {
            throw UpdateCheckException("The release update metadata is not valid JSON.", exception)
        } catch (exception: IllegalArgumentException) {
            throw UpdateCheckException(exception.message ?: "The release update metadata is invalid.", exception)
        }
        if (metadata.versionCode <= currentBuildNumber) return null

        return AppUpdate(
            versionName = metadata.versionName,
            buildNumber = metadata.versionCode,
            commitSha = metadata.commitSha,
            builtAt = metadata.builtAt,
            summary = release.summary,
            assetUrl = metadata.apkUrl,
            assetSha256 = metadata.apkSha256,
            releasePageUrl = release.pageUrl,
        )
    }

    fun close() {
        client.close()
    }

    private suspend fun requestText(
        url: String,
        isLatestReleaseRequest: Boolean = false,
    ): String {
        val response = client.get(url) {
            timeout { requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS }
            headers.append(HttpHeaders.CacheControl, "no-cache")
            headers.append(HttpHeaders.UserAgent, "RSSReader-Updater")
            if (isLatestReleaseRequest) {
                headers.append(HttpHeaders.Accept, "application/vnd.github+json")
            }
        }
        if (response.status.value != 200) {
            throw UpdateCheckException("GitHub release check failed with HTTP ${response.status.value}.")
        }
        val body = response.bodyAsText()
        if (body.length > MAX_RESPONSE_LENGTH) {
            throw UpdateCheckException("GitHub returned an unexpectedly large update response.")
        }
        return body
    }

    private fun parseRelease(body: String): ReleaseInfo {
        val root = Json.parseToJsonElement(body) as? JsonObject
            ?: throw IllegalArgumentException("The GitHub release response must be a JSON object.")
        require(root["draft"]?.jsonPrimitive?.booleanOrNull == false) {
            "The latest GitHub release is a draft."
        }
        require(root["prerelease"]?.jsonPrimitive?.booleanOrNull == false) {
            "The latest GitHub release is a prerelease."
        }

        val tagName = root.string("tag_name")
            ?: throw IllegalArgumentException("The latest GitHub release has no version tag.")
        require(RELEASE_TAG_PATTERN.matches(tagName)) {
            "The latest GitHub release has an invalid version tag."
        }
        val pageUrl = root.string("html_url")
            ?: throw IllegalArgumentException("The latest GitHub release has no details URL.")
        require(pageUrl == "$RELEASE_PAGE_BASE_URL$tagName") {
            "The latest GitHub release has an invalid details URL."
        }
        val assets = root["assets"] as? JsonArray
            ?: throw IllegalArgumentException("The latest GitHub release has no assets.")
        val apkUrl = releaseAssetUrl(assets, ANDROID_APK_NAME, tagName)
        val updateMetadataUrl = releaseAssetUrl(assets, ANDROID_UPDATE_METADATA_NAME, tagName)
        val summary = root.string("body")
            .orEmpty()
            .replace(WHITESPACE_PATTERN, " ")
            .trim()
            .ifBlank { "A newer Android version is available." }

        return ReleaseInfo(
            tagName = tagName,
            versionName = tagName.drop(1),
            apkUrl = apkUrl,
            updateMetadataUrl = updateMetadataUrl,
            pageUrl = pageUrl,
            summary = summary.take(MAX_SUMMARY_LENGTH),
        )
    }

    private fun releaseAssetUrl(
        assets: JsonArray,
        assetName: String,
        tagName: String,
    ): String {
        val asset = assets.firstNotNullOfOrNull { element ->
            val candidate = element as? JsonObject ?: return@firstNotNullOfOrNull null
            if (candidate.string("name") == assetName) candidate else null
        } ?: throw IllegalArgumentException("The latest release is missing $assetName.")
        val url = asset.string("browser_download_url")
        val expectedUrl = "$RELEASE_DOWNLOAD_BASE_URL$tagName/$assetName"
        require(url == expectedUrl) {
            "The latest release contains an unexpected $assetName URL."
        }
        return expectedUrl
    }

    private fun parseUpdateMetadata(
        body: String,
        release: ReleaseInfo,
    ): ReleaseUpdateMetadata {
        val root = Json.parseToJsonElement(body) as? JsonObject
            ?: throw IllegalArgumentException("Release update metadata must be a JSON object.")
        val schemaVersion = root["schemaVersion"]?.jsonPrimitive?.intOrNull
        require(schemaVersion == 1) { "This release update metadata version is not supported." }
        require(root.string("tagName") == release.tagName) {
            "Release update metadata does not match the latest release tag."
        }

        val versionName = root.string("versionName")
            ?: throw IllegalArgumentException("Release update metadata has no version name.")
        require(versionName == release.versionName) {
            "Release update metadata has an invalid version name."
        }
        val versionCode = root["versionCode"]?.jsonPrimitive?.longOrNull
            ?: throw IllegalArgumentException("Release update metadata has an invalid Android version code.")
        require(versionCode in 1L..MAX_SUPPORTED_VERSION_CODE) {
            "Release update metadata has an invalid Android version code."
        }
        val commitSha = root.string("commitSha")
            ?: throw IllegalArgumentException("Release update metadata has an invalid commit SHA.")
        require(COMMIT_SHA_PATTERN.matches(commitSha)) {
            "Release update metadata has an invalid commit SHA."
        }
        val builtAt = root.string("builtAt")
            ?: throw IllegalArgumentException("Release update metadata has no build timestamp.")
        require(builtAt.isNotBlank()) {
            "Release update metadata has no build timestamp."
        }
        val apkUrl = root.string("apkUrl")
            ?: throw IllegalArgumentException("Release update metadata has no APK URL.")
        require(apkUrl == release.apkUrl) {
            "Release update metadata contains an unexpected APK URL."
        }
        val apkSha256 = root.string("apkSha256")
            ?: throw IllegalArgumentException("Release update metadata contains an invalid APK checksum.")
        require(SHA256_PATTERN.matches(apkSha256)) {
            "Release update metadata contains an invalid APK checksum."
        }

        return ReleaseUpdateMetadata(
            versionName = versionName,
            versionCode = versionCode,
            commitSha = commitSha,
            builtAt = builtAt,
            apkUrl = apkUrl,
            apkSha256 = apkSha256.lowercase(),
        )
    }

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull

    private data class ReleaseInfo(
        val tagName: String,
        val versionName: String,
        val apkUrl: String,
        val updateMetadataUrl: String,
        val pageUrl: String,
        val summary: String,
    )

    private data class ReleaseUpdateMetadata(
        val versionName: String,
        val versionCode: Long,
        val commitSha: String,
        val builtAt: String,
        val apkUrl: String,
        val apkSha256: String,
    )

    private companion object {
        const val REQUEST_TIMEOUT_MILLIS = 12_000L
        const val MAX_RESPONSE_LENGTH = 256 * 1024
        const val MAX_SUMMARY_LENGTH = 320
        const val MAX_SUPPORTED_VERSION_CODE = 2_100_000_000L
        val RELEASE_TAG_PATTERN = Regex("^[vV]\\d+\\.\\d+\\.\\d+$")
        val COMMIT_SHA_PATTERN = Regex("^[0-9a-fA-F]{40}$")
        val SHA256_PATTERN = Regex("^[0-9a-fA-F]{64}$")
        val WHITESPACE_PATTERN = Regex("\\s+")
    }
}

class UpdateCheckException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
