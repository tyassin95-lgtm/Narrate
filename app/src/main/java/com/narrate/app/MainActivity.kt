package com.narrate.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.material3.Surface
import com.narrate.app.ui.NarrateApp
import com.narrate.app.ui.theme.NarrateColors
import com.narrate.app.ui.theme.NarrateTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            NarrateTheme {
                Surface(
                    modifier = Modifier.fillMaxSize().background(NarrateColors.Background),
                    color = NarrateColors.Background
                ) {
                    NarrateApp(application)
                }
            }
        }
    }
}
