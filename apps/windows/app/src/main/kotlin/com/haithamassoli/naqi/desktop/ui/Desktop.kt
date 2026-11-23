package com.haithamassoli.naqi.desktop.ui

import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

// The OS hooks: native file dialogs, opening a file, revealing it in the file manager. AWT's FileDialog
// rather than Swing's JFileChooser because it is the real Explorer/Finder dialog on both platforms.

internal val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "mov", "mkv", "webm", "avi", "3gp", "ts", "mts", "wmv", "flv")

internal fun isVideoFile(f: File) = f.isFile && f.extension.lowercase() in VIDEO_EXTENSIONS

internal fun chooseVideoDialog(title: String): File? {
    val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
    // Windows honours the wildcard list in `file`; macOS ignores it and uses the filter instead.
    dialog.file = VIDEO_EXTENSIONS.joinToString(";") { "*.$it" }
    dialog.setFilenameFilter { _, name -> name.substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS }
    dialog.isVisible = true
    return dialog.files.firstOrNull()
}

/** Save dialog seeded with [suggested]; the result always ends in .mp4, the only container Renderer writes. */
internal fun chooseOutputDialog(title: String, suggested: File): File? {
    val dialog = FileDialog(null as Frame?, title, FileDialog.SAVE)
    dialog.directory = suggested.absoluteFile.parent
    dialog.file = suggested.name
    dialog.isVisible = true
    val f = dialog.files.firstOrNull() ?: return null
    return if (f.extension.equals("mp4", ignoreCase = true)) f else File(f.path + ".mp4")
}

internal fun openFile(f: File) {
    runCatching { Desktop.getDesktop().open(f) }
}

/** Explorer and Finder both select the file; anything else gets its folder opened. */
internal fun showInFolder(f: File) {
    runCatching {
        when {
            // One argument "/select,<path>": explorer wants the comma joined to the path.
            isWindows -> ProcessBuilder("explorer.exe", "/select,${f.absolutePath}").start()
            Desktop.getDesktop().isSupported(Desktop.Action.BROWSE_FILE_DIR) -> Desktop.getDesktop().browseFileDirectory(f)
            else -> Desktop.getDesktop().open(f.absoluteFile.parentFile)
        }
    }
}
