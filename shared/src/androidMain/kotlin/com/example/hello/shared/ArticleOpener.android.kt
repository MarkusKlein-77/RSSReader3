package com.example.hello.shared

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberArticleOpener(): (String) -> Unit {
    val context = LocalContext.current
    return remember(context) {
        { url ->
            val firefoxIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                setPackage("org.mozilla.firefox")
                putExtra("private_browsing", true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(firefoxIntent)
            } catch (_: ActivityNotFoundException) {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }
}