package com.example.hello.desktop

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.example.hello.shared.App
import com.example.hello.shared.DesktopConfigurationStore

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "RSS Reader",
    ) {
        App(DesktopConfigurationStore())
    }
}