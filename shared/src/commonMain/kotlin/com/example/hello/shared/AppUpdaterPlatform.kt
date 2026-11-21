package com.example.hello.shared

import androidx.compose.runtime.Composable

@Composable
expect fun rememberAppUpdater(): AppUpdater?
