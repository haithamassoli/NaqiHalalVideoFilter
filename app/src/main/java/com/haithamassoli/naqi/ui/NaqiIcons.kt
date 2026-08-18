package com.haithamassoli.naqi.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

// A tiny hand-built icon set — avoids pulling the large material-icons-extended dependency.
// Fill color is irrelevant: Icon(tint = …) recolors these to the theme role at the call site.
object NaqiIcons {
    val Video = icon("Video") {
        // camera body
        moveTo(5f, 6.5f); lineTo(13f, 6.5f)
        quadTo(15f, 6.5f, 15f, 8.5f); lineTo(15f, 15.5f)
        quadTo(15f, 17.5f, 13f, 17.5f); lineTo(5f, 17.5f)
        quadTo(3f, 17.5f, 3f, 15.5f); lineTo(3f, 8.5f)
        quadTo(3f, 6.5f, 5f, 6.5f); close()
        // lens
        moveTo(15f, 10f); lineTo(20.5f, 7f)
        quadTo(21f, 6.8f, 21f, 7.6f); lineTo(21f, 16.4f)
        quadTo(21f, 17.2f, 20.5f, 17f); lineTo(15f, 14f); close()
    }

    val MusicOff = icon("MusicOff") {
        // three bars
        rect(6.2f, 10f, 7.8f, 14f)
        rect(10.2f, 6.5f, 11.8f, 17.5f)
        rect(14.2f, 9f, 15.8f, 15f)
        // slash
        moveTo(4.0f, 18.6f); lineTo(18.6f, 4.0f); lineTo(20.0f, 5.4f); lineTo(5.4f, 20.0f); close()
    }

    val Shield = icon("Shield") {
        moveTo(12f, 2.5f); lineTo(19.5f, 5.5f); lineTo(19.5f, 11.2f)
        curveTo(19.5f, 15.9f, 16.4f, 19.5f, 12f, 21f)
        curveTo(7.6f, 19.5f, 4.5f, 15.9f, 4.5f, 11.2f)
        lineTo(4.5f, 5.5f); close()
    }

    val Check = icon("Check") {
        moveTo(9.8f, 16.2f); lineTo(5.6f, 12.0f); lineTo(4.2f, 13.4f)
        lineTo(9.8f, 19.0f); lineTo(20.0f, 8.8f); lineTo(18.6f, 7.4f); close()
    }

    // autoMirror: the back arrow has to point at the start edge, which is the right one in Arabic.
    val ArrowBack = icon("ArrowBack", autoMirror = true) {
        moveTo(20f, 11f); lineTo(7.8f, 11f); lineTo(13.4f, 5.4f); lineTo(12f, 4f)
        lineTo(4f, 12f); lineTo(12f, 20f); lineTo(13.4f, 18.6f); lineTo(7.8f, 13f)
        lineTo(20f, 13f); close()
    }

    val Close = icon("Close") {
        moveTo(18.3f, 7.1f); lineTo(16.9f, 5.7f); lineTo(12f, 10.6f); lineTo(7.1f, 5.7f)
        lineTo(5.7f, 7.1f); lineTo(10.6f, 12f); lineTo(5.7f, 16.9f); lineTo(7.1f, 18.3f)
        lineTo(12f, 13.4f); lineTo(16.9f, 18.3f); lineTo(18.3f, 16.9f); lineTo(13.4f, 12f); close()
    }

    /** Arrow into a tray — the update card, and the only place it appears. */
    val Download = icon("Download") {
        // shaft + head
        moveTo(11f, 3f); lineTo(13f, 3f); lineTo(13f, 11.2f); lineTo(16.6f, 7.6f); lineTo(18f, 9f)
        lineTo(12f, 15f); lineTo(6f, 9f); lineTo(7.4f, 7.6f); lineTo(11f, 11.2f); close()
        // tray
        moveTo(4.5f, 16f); lineTo(6.5f, 16f); lineTo(6.5f, 19f); lineTo(17.5f, 19f)
        lineTo(17.5f, 16f); lineTo(19.5f, 16f); lineTo(19.5f, 21f); lineTo(4.5f, 21f); close()
    }

    /**
     * Three nodes joined by two edges. The edges are drawn first and run all the way to the node
     * centres, so the nodes cover their ends and no seam shows — every subpath here winds the same
     * way as [circle], which is what keeps the non-zero fill solid instead of punching holes.
     */
    val Share = icon("Share") {
        moveTo(5.6f, 11.2f); lineTo(17.6f, 5.2f); lineTo(18.4f, 6.8f); lineTo(6.4f, 12.8f); close()
        moveTo(6.4f, 11.2f); lineTo(18.4f, 17.2f); lineTo(17.6f, 18.8f); lineTo(5.6f, 12.8f); close()
        circle(18f, 6f, 2.4f); circle(6f, 12f, 2.4f); circle(18f, 18f, 2.4f)
    }

    /** Overflow "kebab" — carries the entries that used to be full-width cards on the pick screen. */
    val More = icon("More") {
        circle(12f, 5.2f, 1.9f); circle(12f, 12f, 1.9f); circle(12f, 18.8f, 1.9f)
    }
}

private fun icon(name: String, autoMirror: Boolean = false, path: PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
        autoMirror = autoMirror,
    ).apply {
        path(fill = SolidColor(Color.Black)) { path() }
    }.build()

private fun PathBuilder.rect(left: Float, top: Float, right: Float, bottom: Float) {
    moveTo(left, top); lineTo(right, top); lineTo(right, bottom); lineTo(left, bottom); close()
}

/** Two half-arcs — the only way to get a round dot out of a path builder. */
private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
    moveTo(cx - r, cy)
    arcTo(r, r, 0f, false, true, cx + r, cy)
    arcTo(r, r, 0f, false, true, cx - r, cy)
    close()
}
