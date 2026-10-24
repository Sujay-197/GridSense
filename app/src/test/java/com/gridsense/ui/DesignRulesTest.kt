package com.gridsense.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the design-system rule that colours are only defined in the theme file. */
class DesignRulesTest {

    @Test
    fun noColourLiteralsOutsideTheTheme() {
        val root = File("src/main/java")
        assertTrue("run from the app module", root.isDirectory)
        val literal = Regex("""Color\(0x|Color\.(White|Black|Red|Green|Blue|Gray|Yellow)\b""")
        val offenders = root.walk()
            .filter { it.isFile && it.extension == "kt" && it.name != "Theme.kt" }
            .flatMap { f ->
                f.readLines().mapIndexedNotNull { i, line ->
                    if (literal.containsMatchIn(line)) "${f.name}:${i + 1}" else null
                }
            }
            .toList()
        assertTrue("colour literals outside Theme.kt: $offenders", offenders.isEmpty())
    }
}
