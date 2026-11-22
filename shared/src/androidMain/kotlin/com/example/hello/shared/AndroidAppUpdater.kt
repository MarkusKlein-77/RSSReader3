package com.example.hello.shared

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

@Composable
actual fun rememberAppUpdater(): AppUpdater? {
    val context = androidx.compose.ui.platform.LocalContext.current
    return remember(context) { AndroidAppUpdater(context) }
}

private class AndroidAppUpdater(
    private val context: Context,
) : AppUpdater {
    override val currentVersionName: String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
            ?: throw IllegalStateException("The installed app version is unavailable.")

    private val checker = ReleaseUpdateChecker(
        currentBuildNumber = installedBuildNumber(),
    )

    override suspend fun checkForUpdate(): AppUpdate? = checker.checkForUpdate()

    override suspend fun install(update: AppUpdate) {
        val apk = withContext(Dispatchers.IO) { downloadAndVerify(update) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            throw IllegalStateException(
                "Allow RSS Reader to install updates in system settings, then choose Update again.",
            )
        }

        val apkUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk,
        )
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, APK_MIME_TYPE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }

    override fun close() {
        checker.close()
    }

    private suspend fun downloadAndVerify(update: AppUpdate): File {
        val directory = File(context.cacheDir, "updates").apply {
            if (!exists() && !mkdirs()) {
                throw IllegalStateException("Could not create the update download directory.")
            }
        }
        val apk = File(directory, "RSSReader-${update.buildNumber}.apk")
        val connection = URL(update.assetUrl).openConnection() as? HttpsURLConnection
            ?: throw IllegalStateException("Update packages must be downloaded over HTTPS.")
        try {
            connection.connectTimeout = NETWORK_TIMEOUT_MILLIS
            connection.readTimeout = NETWORK_TIMEOUT_MILLIS
            if (connection.responseCode != HttpsURLConnection.HTTP_OK) {
                throw IllegalStateException("Could not download the update (HTTP ${connection.responseCode}).")
            }
            if (connection.url.protocol != "https") {
                throw IllegalStateException("Update packages must be downloaded over HTTPS.")
            }
            if (connection.contentLengthLong > MAX_APK_SIZE) {
                throw IllegalStateException("The update package is too large to install.")
            }
            val digest = MessageDigest.getInstance("SHA-256")
            var totalBytes = 0L
            connection.inputStream.use { input ->
                FileOutputStream(apk).use { output ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        totalBytes += count
                        if (totalBytes > MAX_APK_SIZE) {
                            throw IllegalStateException("The update package is too large to install.")
                        }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
            }
            if (digest.digest().toHex() != update.assetSha256) {
                apk.delete()
                throw IllegalStateException("The downloaded update failed its SHA-256 integrity check.")
            }
            return apk
        } catch (exception: Exception) {
            apk.delete()
            throw exception
        } finally {
            connection.disconnect()
        }
    }

    @Suppress("DEPRECATION")
    private fun installedBuildNumber(): Long {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            packageInfo.versionCode.toLong()
        }
    }

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private companion object {
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        const val NETWORK_TIMEOUT_MILLIS = 30_000
        const val MAX_APK_SIZE = 200L * 1024 * 1024
        const val DOWNLOAD_BUFFER_SIZE = 16 * 1024
    }
}
