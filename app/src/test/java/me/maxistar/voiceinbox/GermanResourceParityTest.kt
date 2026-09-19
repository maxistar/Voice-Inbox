package me.maxistar.voiceinbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class GermanResourceParityTest {
    @Test
    fun germanResourcesMatchDefaultNamesAndFormatArguments() {
        val defaultDocument = parse("src/main/res/values/strings.xml")
        val germanDocument = parse("src/main/res/values-de/strings.xml")
        val defaultEntries = entries(defaultDocument)
        val germanEntries = entries(germanDocument)

        assertEquals(defaultEntries.keys, germanEntries.keys)
        defaultEntries.forEach { (name, value) ->
            assertEquals("format arguments for $name", placeholders(value), placeholders(germanEntries.getValue(name)))
        }
    }

    @Test
    fun germanLocaleContainsTranslatedVoiceInboxCopy() {
        val document = parse("src/main/res/values-de/strings.xml")
        val entries = entries(document)
        assertEquals("Sprachmodell installieren", entries.getValue("task_install_speech_model"))
        assertEquals("Voice-Inbox-Tastatur", entries.getValue("voice_keyboard_name"))
        assertTrue(entries.getValue("onboarding_title").contains("einrichten", ignoreCase = true))
    }

    private fun parse(path: String) = DocumentBuilderFactory.newInstance()
        .newDocumentBuilder()
        .parse(File(path))
        .documentElement

    private fun entries(root: Element): Map<String, String> = buildMap {
        val nodes = root.childNodes
        for (index in 0 until nodes.length) {
            val node = nodes.item(index)
            if (node is Element && node.tagName == "string") {
                put(node.getAttribute("name"), node.textContent)
            } else if (node is Element && node.tagName == "plurals") {
                for (childIndex in 0 until node.childNodes.length) {
                    val child = node.childNodes.item(childIndex)
                    if (child is Element && child.tagName == "item") {
                        put("${node.getAttribute("name")}:${child.getAttribute("quantity")}", child.textContent)
                    }
                }
            }
        }
    }

    private fun placeholders(value: String): List<String> = "%\\d+\\$[sd]".toRegex().findAll(value).map { it.value }.toList()
}
