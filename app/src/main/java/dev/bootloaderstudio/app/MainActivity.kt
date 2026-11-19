package dev.bootloaderstudio.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import dev.bootloaderstudio.app.ui.BootloaderStudioApp
import dev.bootloaderstudio.app.ui.theme.BootloaderStudioTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BootloaderStudioTheme {
                BootloaderStudioApp()
            }
        }
    }
}
