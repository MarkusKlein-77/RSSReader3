package com.example.hello.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.browser.window

@Composable
actual fun rememberArticleOpener(): (String) -> Unit = remember {
    { url -> window.open(url, "_blank") }
}