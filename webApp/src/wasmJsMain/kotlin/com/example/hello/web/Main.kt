@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.example.hello.web

import androidx.compose.ui.window.ComposeViewport
import com.example.hello.shared.App
import kotlinx.browser.document

fun main() {
    ComposeViewport(document.body!!) {
        App()
    }
}