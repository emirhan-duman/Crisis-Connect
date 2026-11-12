package com.auralis.crisisconnect.muster

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

class MusterLocalizationTest {
    @Test fun allSupportedLanguagesHaveCompleteMusterCopyAndPreserveFormatArguments() {
        val resources = File("src/main/res")
        val languages = listOf("", "-tr", "-ar", "-bn", "-de", "-es", "-fa", "-fil", "-fr", "-hi", "-id", "-ja", "-ku", "-pt", "-ru", "-uk", "-ur", "-vi", "-zh-rCN")
        fun read(suffix: String): Map<String, String> {
            val values = mutableMapOf<String, String>()
            for (filename in listOf("muster_strings.xml", "muster_alert_strings.xml")) {
                val file = File(resources, "values$suffix/$filename")
                assertTrue(file.path, file.isFile)
                val factory = DocumentBuilderFactory.newInstance().apply { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                val nodes = factory.newDocumentBuilder().parse(file).getElementsByTagName("string")
                for (index in 0 until nodes.length) {
                    val node = nodes.item(index)
                    val key = node.attributes.getNamedItem("name").nodeValue
                    assertNull("Duplicate $suffix.$key", values.put(key, node.textContent))
                }
            }
            return values
        }
        val english = read("")
        val formats = Regex("%[0-9]+\\$[ds]")
        for (language in languages) {
            val localized = read(language)
            assertEquals(language, english.keys, localized.keys)
            for ((key, original) in english) {
                val value = localized.getValue(key)
                assertTrue("$language.$key", value.isNotBlank())
                if (language.isNotEmpty()) assertFalse("Untranslated artifact $language.$key", Regex("\\bclosed\\b|CCX[0-9]+XCC").containsMatchIn(value))
                assertEquals("$language.$key", formats.findAll(original).map { it.value }.sorted().toList(), formats.findAll(value).map { it.value }.sorted().toList())
            }
            if (language.isNotEmpty()) for (key in listOf("muster_title", "muster_help", "muster_sent")) assertNotEquals("$language.$key", english[key], localized[key])
        }
    }
}
