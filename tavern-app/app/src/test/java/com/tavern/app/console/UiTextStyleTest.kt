package com.tavern.app.console

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Checks the console's text style mechanically, because it is a rule a reviewer has to remember
 * and a mechanical rule is better checked by a machine: values are separated with a Chinese
 * comma, never with a middle dot.
 *
 * The one legitimate occurrence is the display normaliser in the memory screen, which is the code
 * that strips the character out of text the engine wrote before the rule existed.
 */
class UiTextStyleTest {

    @Test
    fun uiSourcesSeparateValuesWithoutAMiddleDot() {
        val root = File("src/main/java")
        assertTrue("app sources not found at ${root.absolutePath}", root.isDirectory)

        val offenders = mutableListOf<String>()
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                file.readLines().forEachIndexed { index, line ->
                    if (!line.contains(" · ")) return@forEachIndexed
                    if (line.contains("replace(\" · \"")) return@forEachIndexed
                    offenders += "${file.name}:${index + 1}: ${line.trim()}"
                }
            }

        assertTrue(
            "separator found in app text:\n" + offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }
}
