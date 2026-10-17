package ch.vitosstudios.tippli.core

import ch.vitosstudios.tippli.core.data.TippliJson
import ch.vitosstudios.tippli.core.text.TextAlgorithms
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies SPEC §5.2–5.6 against every vector in shared/tests/engine-cases.json. */
class EngineCasesTest {

    private val cases: JsonObject =
        TippliJson.parseToJsonElement(TestShared.read("tests/engine-cases.json")).jsonObject

    private fun group(name: String): List<JsonObject> = cases.getValue(name).jsonArray.map { it.jsonObject }

    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.bool(key: String) = getValue(key).jsonPrimitive.boolean
    private fun JsonElement.isNull() = this is JsonNull

    @Test
    fun selectSegment() {
        val all = group("selectSegment")
        assertTrue(all.isNotEmpty())
        for (c in all) {
            // Optional field: absent means 0 (SPEC §5.2 step 0).
            val minStart = c["minStart"]?.jsonPrimitive?.int ?: 0
            val result = TextAlgorithms.selectSegment(c.str("before"), c.bool("explicit"), c.bool("pinyin"), minStart)
            val expected = c.getValue("expected")
            val label = "selectSegment(${c.str("before").take(60)}…, explicit=${c.bool("explicit")}, " +
                "pinyin=${c.bool("pinyin")}, minStart=$minStart)"
            if (expected.isNull()) {
                assertEquals(label, null, result)
            } else {
                val e = expected.jsonObject
                assertEquals("$label context", e.str("context"), result?.context)
                assertEquals("$label segment", e.str("segment"), result?.segment)
                assertEquals("$label trailing", e.str("trailing"), result?.trailing)
            }
        }
    }

    @Test
    fun cleanOutput() {
        val all = group("cleanOutput")
        assertTrue(all.isNotEmpty())
        for (c in all) {
            val result = TextAlgorithms.cleanOutput(c.str("segment"), c.str("output"), c.bool("pinyin"))
            val expected = c.getValue("expected")
            val label = "cleanOutput(${c.str("segment")} / ${c.str("output")})"
            assertEquals(label, if (expected.isNull()) null else expected.jsonPrimitive.content, result)
        }
    }

    @Test
    fun computeEdit() {
        val all = group("computeEdit")
        assertTrue(all.isNotEmpty())
        for (c in all) {
            val result = TextAlgorithms.computeEdit(c.str("segment"), c.str("corrected"), c.str("suffix"))
            val expected = c.getValue("expected")
            val label = "computeEdit(${c.str("segment")} → ${c.str("corrected")})"
            if (expected.isNull()) {
                assertEquals(label, null, result)
            } else {
                assertEquals("$label delete", expected.jsonObject.str("delete"), result?.delete)
                assertEquals("$label insert", expected.jsonObject.str("insert"), result?.insert)
            }
        }
    }

    @Test
    fun wordDelete() {
        val all = group("wordDelete")
        assertTrue(all.isNotEmpty())
        for (c in all) {
            assertEquals("wordDelete(${c.str("before")})", c.str("expected"), TextAlgorithms.wordDelete(c.str("before")))
        }
    }

    @Test
    fun autoCapitalize() {
        val all = group("autoCapitalize")
        assertTrue(all.isNotEmpty())
        for (c in all) {
            assertEquals("autoCapitalize(${c.str("before")})", c.bool("expected"), TextAlgorithms.autoCapitalize(c.str("before")))
        }
    }

    @Test
    fun doubleSpacePeriod() {
        val all = group("doubleSpacePeriod")
        assertTrue(all.isNotEmpty())
        for (c in all) {
            assertEquals(
                "doubleSpacePeriod(${c.str("before")})",
                c.bool("expected"),
                TextAlgorithms.doubleSpacePeriod(c.str("before")),
            )
        }
    }

    @Test
    fun canApply() {
        val all = group("canApply")
        assertTrue(all.isNotEmpty())
        for (c in all) {
            assertEquals(
                "canApply(${c.str("beforeNow")})",
                c.bool("expected"),
                TextAlgorithms.canApply(c.str("beforeNow"), c.str("segment"), c.str("trailing"), c.str("typedSince")),
            )
        }
    }
}
