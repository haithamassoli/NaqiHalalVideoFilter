package com.haithamassoli.naqi.desktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

// Owner: the UI agent. Contract in apps/windows/SEAMS.md. A placeholder rather than TODO() so
// `./gradlew :app:run` opens a window while the screens are being written.
@Composable
fun App() {
    MaterialTheme {
        Text("Naqi")
    }
}
