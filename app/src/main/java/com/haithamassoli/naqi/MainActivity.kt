package com.haithamassoli.naqi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.haithamassoli.naqi.ui.screen.PickOpsScreen
import com.haithamassoli.naqi.ui.theme.NaqiTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NaqiTheme {
                PickOpsScreen(modifier = Modifier.fillMaxSize())
            }
        }
    }
}
