package com.haithamassoli.naqi.desktop.ui

/**
 * The open-source notices the About screen shows: apps/windows/NOTICE, which the build copies into the jar
 * next to this class (and, with LICENSE, into the installed app's resources folder). The entries shared with
 * Android are copied from the repository's root `NOTICE`; keep them in step with that file.
 *
 * Not translated, as on Android: a licence notice is a legal text, and a translation would be a second,
 * unreviewed version of it.
 */
internal val NOTICE: String = object {}.javaClass.getResource("NOTICE")!!.readText()
