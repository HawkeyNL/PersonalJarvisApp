package com.hawkeynl.jarvis.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Colours and gradients come from InstrumentTheme.kt tokens only, never from literals in screens. */
class ColorLiteralsTest {
    @Test
    fun `no colour or brush literals outside InstrumentTheme`() {
        val theme = JvColorsTest.repoFile("android/app/src/main/java/com/hawkeynl/jarvis/ui/InstrumentTheme.kt")
        val android = theme.path.removeSuffix("app/src/main/java/com/hawkeynl/jarvis/ui/InstrumentTheme.kt")
        val sources = java.io.File(android).walkTopDown()
            .filter { it.isFile && it.extension == "kt" && "${java.io.File.separator}src${java.io.File.separator}main${java.io.File.separator}" in it.path }
            .toList()
        assertTrue("found only ${sources.size} main sources under $android", sources.size > 20)
        val offenders = sources.filter { it.canonicalPath != theme.canonicalPath }.flatMap { file ->
            file.readLines().mapIndexedNotNull { i, line ->
                if ("Color(0x" in line || "Brush." in line) "${file.name}:${i + 1}: ${line.trim()}" else null
            }
        }
        assertEquals("move these into InstrumentTheme.kt tokens", emptyList<String>(), offenders)
    }
}
