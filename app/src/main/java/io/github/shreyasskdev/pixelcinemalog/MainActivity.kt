// File: MainActivity.kt
// Path: app/src/main/java/io/github/shreyasskdev/pixelcinemalog/MainActivity.kt
package io.github.shreyasskdev.pixelcinemalog

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.shreyasskdev.pixelcinemalog.ui.CinemaCameraScreen
import io.github.shreyasskdev.pixelcinemalog.ui.theme.PixelCinemaLogTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { PixelCinemaLogTheme { CinemaCameraScreen() } }
    }
}
