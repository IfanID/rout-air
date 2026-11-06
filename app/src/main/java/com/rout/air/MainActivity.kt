package com.rout.air

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.rout.air.ui.RoutAirApp
import com.rout.air.ui.theme.RoutAirTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.graphics.toArgb

class MainActivity : ComponentActivity() {
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getStringExtra("open_tab") == "studio") {
            com.rout.air.data.common.UiEvents.openStudio.tryEmit(Unit)
        } else if (intent.getStringExtra("open_tab") == "live") {
            com.rout.air.data.common.UiEvents.openLiveSetup.tryEmit(Unit)
        } else if (intent.getStringExtra("open_tab") == "settings") {
            com.rout.air.data.common.UiEvents.openSettings.tryEmit(Unit)
        }
    }

    override fun onStart() {
        super.onStart()
        visible = true
    }

    override fun onStop() {
        visible = false
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.Transparent.toArgb()),
            navigationBarStyle = SystemBarStyle.dark(Color.Transparent.toArgb()),
        )
        setContent {
            RoutAirTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    RoutAirApp(
                        initialStudio = intent?.getStringExtra("open_tab") == "studio",
                        initialLiveSetup = intent?.getStringExtra("open_tab") == "live",
                        initialSettings = intent?.getStringExtra("open_tab") == "settings",
                        modifier = Modifier.safeDrawingPadding(),
                    )
                }
            }
        }
    }

    companion object {
        /** Aplikasi sedang terlihat? Dipakai overlay agar tidak merebut pratinjau. */
        @Volatile
        var visible: Boolean = false
    }
}
