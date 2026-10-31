package com.rout.air

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.rout.air.ui.RoutAirApp
import com.rout.air.ui.theme.RoutAirTheme

class MainActivity : ComponentActivity() {
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
                    RoutAirApp(modifier = Modifier.safeDrawingPadding())
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
