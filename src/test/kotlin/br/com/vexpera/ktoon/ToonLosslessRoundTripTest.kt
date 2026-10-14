package br.com.vexpera.ktoon

import br.com.vexpera.ktoon.decoder.DecoderOptions
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * encode → decode must give back the same values with the same types,
 * including strings that look like other TOON tokens and lists at the root.
 */
class ToonLosslessRoundTripTest {

    private val trickyStrings = listOf(
        "Cable HDMI, 2 m", "a|b", "tab\there", "42", "12.9", "-1", "1e3", "007", "true", "false", "null",
        "", " leading", "trailing ", "a:b", "-promo", "line\nbreak", "cr\rhere", "quote\"inside", "back\\slash", "[x]", "{y}",
    )

    private fun roundTrip(value: Any?, options: EncodeOptions = EncodeOptions()): Any? =
        Toon.decode(Toon.encode(value, options))

    @Test
    fun tableCellsKeepEveryStringAsString() {
        for (delimiter in Delimiter.entries) {
            val rows = trickyStrings.mapIndexed { i, s -> linkedMapOf("id" to i.toLong(), "text" to s) }
            val back = roundTrip(linkedMapOf("rows" to rows), EncodeOptions(delimiter = delimiter)) as Map<*, *>
            assertEquals(rows, back["rows"], "delimiter=$delimiter")
            (back["rows"] as List<*>).forEach { assertIs<String>((it as Map<*, *>)["text"], "delimiter=$delimiter") }
        }
    }

    @Test
    fun inlinePrimitiveArraysKeepEveryStringAsString() {
        for (delimiter in Delimiter.entries) {
            val back = roundTrip(linkedMapOf("tags" to trickyStrings), EncodeOptions(delimiter = delimiter)) as Map<*, *>
            assertEquals(trickyStrings, back["tags"], "delimiter=$delimiter")
        }
    }

    @Test
    fun objectValuesKeepEveryStringAsString() {
        val obj = LinkedHashMap<String, Any?>()
        trickyStrings.forEachIndexed { i, s -> obj["k$i"] = s }
        assertEquals(obj, roundTrip(obj))
    }

    @Test
    fun mixedCellTypesSurvive() {
        val rows = listOf(
            linkedMapOf("sku" to "007", "price" to 12.9.toBigDecimal(), "stock" to 140L, "active" to true, "tag" to "true", "note" to null),
            linkedMapOf("sku" to "1042", "price" to 34.5.toBigDecimal(), "stock" to 0L, "active" to false, "tag" to "null", "note" to "x, y"),
        )
        assertEquals(linkedMapOf("products" to rows), roundTrip(linkedMapOf("products" to rows)))
    }

    @Test
    fun rootListOfObjectsDecodesAsList() {
        val rows = listOf(linkedMapOf("sensor" to "s1", "temp" to 26.5.toBigDecimal()), linkedMapOf("sensor" to "s2", "temp" to 29.1.toBigDecimal()))
        val text = Toon.encode(rows)
        assertTrue(text.startsWith("[2]{sensor,temp}:"), text)
        assertEquals(rows, Toon.decode(text))
    }

    @Test
    fun rootListOfPrimitivesDecodesAsList() {
        val values = listOf("a", "42", 7L, true, null)
        val text = Toon.encode(values)
        assertTrue(text.startsWith("[5]: "), text)
        assertEquals(values, Toon.decode(text))
    }

    @Test
    fun decoderDoesNotWriteToStdout() {
        val text = Toon.encode(linkedMapOf("rows" to listOf(linkedMapOf("a" to "x, y", "b" to 1L))))
        val lenientInput = "root:\n   child: 1"
        val captured = ByteArrayOutputStream()
        val original = System.out
        System.setOut(PrintStream(captured, true))
        try {
            Toon.decode(text)
            Toon.decode(text, DecoderOptions(strict = false))
            Toon.decode(lenientInput, DecoderOptions(strict = false))
        } finally {
            System.setOut(original)
        }
        assertEquals("", captured.toString(), "the decoder must not print decoded content unless debug = true")
    }
}
