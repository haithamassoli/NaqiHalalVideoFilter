package com.haithamassoli.naqi.desktop

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.haithamassoli.naqi.desktop.ui.App

// Owner: the UI agent.
fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "Naqi") {
        App()
    }
}
