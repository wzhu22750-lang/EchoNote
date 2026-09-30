package com.echonote.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.echonote.app.data.settings.EchoNoteSettings
import com.echonote.app.navigation.EchoNoteApp
import com.echonote.app.ui.theme.EchoNoteTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as EchoNoteApplication).container

        setContent {
            val settings by container.settings.settings
                .collectAsStateWithLifecycle(initialValue = EchoNoteSettings())

            EchoNoteTheme(
                darkTheme = isSystemInDarkTheme(),
                dynamicColor = settings.dynamicColor,
            ) {
                EchoNoteApp(container = container)
            }
        }
    }
}
