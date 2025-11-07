package br.com.vexpera.ktoon

import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.*

class ToonSpecComplianceTest {

    private fun readSpecFile(name: String): String {
        val uri = javaClass.classLoader.getResource("spec/$name")?.toURI()
            ?: error("Spec file not found: $name")
        return Files.readString(Paths.get(uri))
    }

    // 1️⃣ length marker (#)
    @Test
    fun decodeTableWithLengthMarker() {
        val text = readSpecFile("01_length_marker.toon")
        val data = Toon.decode(text)
        val users = (data as Map<*, *>)["users"] as List<Map<String, Any?>>
        assertEquals(2, users.size)
        assertEquals("Bob", users[1]["name"])
    }

    // 2️⃣ row count mismatch (strict mode)
    @Test
    fun strictModeRejectsWrongRowCount() {
        val text = readSpecFile("02_wrong_row_count.toon")
        val ex = assertFailsWith<ToonParseException> {
            Toon.decode(text, DecodeOptions(strict = true))
        }
        assertTrue(ex.message!!.contains("Expected", ignoreCase = true))
    }

    // 3️⃣ lenient indentation allowed
    @Test
    fun lenientModeAllowsImperfectIndentation() {
        val text = readSpecFile("03_lenient_indentation.toon")

        // Strict mode must fail
        assertFailsWith<ToonParseException> {
            Toon.decode(text, DecodeOptions(strict = true))
        }

        // Lenient mode should succeed
        val decoded = Toon.decode(text, DecodeOptions(strict = false)) as Map<*, *>
        val app = decoded["app"] as Map<*, *>
        assertEquals("Demo", app["name"])
    }

    // 4️⃣ tabs in indentation rejected (strict)
    @Test
    fun strictModeRejectsTabsInIndentation() {
        val text = readSpecFile("04_tabs_in_indentation.toon")
        val ex = assertFailsWith<ToonParseException> {
            Toon.decode(text, DecodeOptions(strict = true))
        }
        assertTrue(ex.message!!.contains("tab", ignoreCase = true))
    }

    // 5️⃣ NaN and Infinity normalization
    @Test
    fun encodeNormalizesNaNAndInfinityToNull() {
        val text = readSpecFile("05_nan_infinity.toon")
        // This case is purely encoding-side, we simulate encoding
        val obj = mapOf(
            "ok" to 42,
            "nan" to Double.NaN,
            "inf" to Double.POSITIVE_INFINITY,
            "ninf" to Double.NEGATIVE_INFINITY
        )
        val encoded = Toon.encode(obj)
        assertEquals(text.trim(), encoded.trim())
    }

    // 6️⃣ canonical number formatting
    @Test
    fun encoderNormalizesTrailingZerosAndExponents() {
        val expected = readSpecFile("06_canonical_numbers.toon").trim()
        val obj = mapOf(
            "a" to 1.5000,
            "b" to 1e-3,
            "c" to 0.000001,
            "d" to -0.0
        )
        val encoded = Toon.encode(obj).trim()
        assertEquals(expected, encoded)
    }

    // 7️⃣ inline primitive array
    @Test
    fun decodeInlinePrimitiveArray() {
        val text = readSpecFile("07_inline_array.toon")
        val data = Toon.decode(text)
        val tags = (data as Map<*, *>)["tags"] as List<*>
        assertEquals(listOf("red", "green", "blue"), tags)
    }

    // 8️⃣ blank line inside table (strict)
    @Test
    fun strictModeRejectsBlankLinesInsideTable() {
        val text = readSpecFile("08_blank_line_strict.toon")
        val ex = assertFailsWith<ToonParseException> {
            Toon.decode(text, DecodeOptions(strict = true))
        }
        assertTrue(ex.message!!.contains("blank", ignoreCase = true))
    }

    // 9️⃣ blank line ignored (lenient)
    @Test
    fun lenientModeIgnoresBlankLinesInsideTable() {
        val text = readSpecFile("09_blank_line_lenient.toon")
        val decoded = Toon.decode(text, DecodeOptions(strict = false))
        val users = (decoded as Map<*, *>)["users"] as List<Map<String, Any?>>
        assertEquals(2, users.size)
    }

    // 🔟 unsupported list items
    @Test
    fun unimplementedListItemShouldThrowInStrictMode() {
        val text = readSpecFile("10_unsupported_list_items.toon")
        val ex = assertFailsWith<ToonParseException> {
            Toon.decode(text, DecodeOptions(strict = true))
        }
        assertTrue(
            ex.message!!.contains("Unsupported", ignoreCase = true) ||
            ex.message!!.contains("not implemented", ignoreCase = true)
        )
    }
}
