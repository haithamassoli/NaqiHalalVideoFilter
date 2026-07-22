package com.haithamassoli.naqi

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.haithamassoli.naqi.model.FilterOps
import com.haithamassoli.naqi.ui.screen.PickOpsScreen
import com.haithamassoli.naqi.ui.theme.NaqiTheme
import com.haithamassoli.naqi.work.JobController
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (BuildConfig.DEBUG) maybeAutorun()
        setContent {
            NaqiTheme {
                PickOpsScreen(modifier = Modifier.fillMaxSize())
            }
        }
    }

    /** Debug E2E entry point: `-e autorun_path <file>` starts a censor job with no permission prompts. */
    private fun maybeAutorun() {
        val path = intent.getStringExtra("autorun_path") ?: return
        val ops = FilterOps(
            censorWomen = true,
            strictness = intent.getIntExtra("strictness", 50),
            blurAmount = intent.getIntExtra("blur", 60),
            grayscale = intent.getBooleanExtra("grayscale", false),
            blurUnknownFaces = intent.getBooleanExtra("blur_unknown", false),
        )
        JobController.start(this, ops, Uri.fromFile(File(path)).toString(), intent.getStringExtra("force_intervals_ms"))
    }
}
