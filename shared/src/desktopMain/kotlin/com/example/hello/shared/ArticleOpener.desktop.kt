package com.example.hello.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.awt.Desktop
import java.io.File
import java.net.URI

@Composable
actual fun rememberArticleOpener(): (String) -> Unit = remember {
    { url ->
        val firefox = firefoxExecutable()
        if (firefox != null) {
            try {
                ProcessBuilder(firefox, "--private-window", url).start()
            } catch (_: Exception) {
                Desktop.getDesktop().browse(URI(url))
            }
        } else {
            Desktop.getDesktop().browse(URI(url))
        }
    }
}

private fun firefoxExecutable(): String? {
    val candidates = listOfNotNull(
        System.getenv("ProgramFiles")?.let { "$it\\Mozilla Firefox\\firefox.exe" },
        System.getenv("ProgramFiles(x86)")?.let { "$it\\Mozilla Firefox\\firefox.exe" },
        System.getenv("LOCALAPPDATA")?.let { "$it\\Mozilla Firefox\\firefox.exe" },
    )
    return candidates.firstOrNull { File(it).isFile } ?: "firefox"
}