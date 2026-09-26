package com.example.hello.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.example.hello.shared.App
import com.example.hello.shared.AndroidConfigurationStore

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val configurationStore = AndroidConfigurationStore(applicationContext)
        setContent { App(configurationStore) }
    }
}