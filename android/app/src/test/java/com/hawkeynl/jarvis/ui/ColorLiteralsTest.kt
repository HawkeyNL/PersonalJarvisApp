package com.hawkeynl.jarvis.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Colours and gradients come from InstrumentTheme.kt tokens only, never from literals in screens. */
class ColorLiteralsTest {
    @Test
    fun `no colour or brush literals outside InstrumentTheme`() {
        // Unit tests run in android/app; walk up to the android/ directory.
        val android = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "android") }
            .first { File(it, "app/src/main").isDirectory }
        val theme = File(android, "app/src/main/java/com/hawkeynl/jarvis/ui/InstrumentTheme.kt")
        assertTrue("$theme is missing", theme.isFile)
        val sources = listOf("app/src/main", "realtime-core/src/main")
            .flatMap { File(android, it).walkTopDown().filter { f -> f.isFile && f.extension == "kt" }.toList() }
        assertTrue("found only ${sources.size} main sources under $android", sources.size > 20)
        val offenders = sources.filter { it.canonicalPath != theme.canonicalPath }.flatMap { file ->
            file.readLines().mapIndexedNotNull { i, line ->
                if ("Color(0x" in line || "Brush." in line) "${file.name}:${i + 1}: ${line.trim()}" else null
            }
        }
        assertEquals("move these into InstrumentTheme.kt tokens", emptyList<String>(), offenders)
    }
}
