package br.com.vexpera.ktoon

import br.com.vexpera.ktoon.decoder.DecoderOptions
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exact encoder output for headers and quoting (TOON spec v1.4 §5, §6, §7.2, §11),
 * the debug gate of the decoder output, and a seeded round-trip fuzz over tricky strings.
 */
class ToonEncoderRulesTest {

    private fun row(vararg pairs: Pair<String, Any?>) = linkedMapOf(*pairs)

    // ---------- Headers (§5, §6) ----------

    @Test
    fun rootTabularArrayHeaderHasNoKey() {
        val rows = listOf(row("a" to 1L, "b" to "x"), row("a" to 2L, "b" to "y"))
        assertEquals("[2]{a,b}:\n  1,x\n  2,y", Toon.encode(rows))
    }

    @Test
    fun rootInlineArrayHeaderHasNoKey() {
        assertEquals("[3]: a,b,c", Toon.encode(listOf("a", "b", "c")))
    }

    @Test
    fun nonCommaDelimiterIsDeclaredInsideTheBrackets() {
        val data = row("rows" to listOf(row("a" to 1L, "b" to "x")), "tags" to listOf("p", "q"))
        assertEquals("rows[1\t]{a\tb}:\n  1\tx\ntags[2\t]: p\tq", Toon.encode(data, EncodeOptions(delimiter = Delimiter.TAB)))
        assertEquals("rows[1|]{a|b}:\n  1|x\ntags[2|]: p|q", Toon.encode(data, EncodeOptions(delimiter = Delimiter.PIPE)))
    }

    @Test
    fun lengthMarkerComesBeforeTheDelimiterSymbol() {
        val rows = listOf(row("a" to 1L), row("a" to 2L))
        assertEquals("[#2|]{a}:\n  1\n  2", Toon.encode(rows, EncodeOptions(delimiter = Delimiter.PIPE, lengthMarker = true)))
        assertEquals("tags[#2]: x,y", Toon.encode(row("tags" to listOf("x", "y")), EncodeOptions(lengthMarker = true)))
    }

    @Test
    fun rootArraysDecodeBackForEveryDelimiterAndLengthMarker() {
        val rows = listOf(row("id" to 1L, "note" to "a,b|c"), row("id" to 2L, "note" to "true"))
        val values = listOf("x", "1", 2L, null, false)
        for (delimiter in Delimiter.entries) for (marker in listOf(false, true)) {
            val options = EncodeOptions(delimiter = delimiter, lengthMarker = marker)
            assertEquals(rows, Toon.decode(Toon.encode(rows, options)), "rows, $options")
            assertEquals(values, Toon.decode(Toon.encode(values, options)), "values, $options")
        }
    }

    // ---------- Quoting (§7.2, §11) ----------

    /** (value, delimiter, how the cell must be written) */
    private val cellCases = listOf(
        Triple("plain", Delimiter.COMMA, "plain"),
        Triple("two  words", Delimiter.COMMA, "two  words"),
        Triple("ação é", Delimiter.COMMA, "ação é"),
        Triple("a.b", Delimiter.COMMA, "a.b"),
        // "#" at the start of a line is a comment for the decoder (and in spec 4.x), so it is quoted.
        Triple("#tag", Delimiter.COMMA, "\"#tag\""),
        Triple("42", Delimiter.COMMA, "\"42\""),
        Triple("-3.14", Delimiter.COMMA, "\"-3.14\""),
        Triple("1e-6", Delimiter.COMMA, "\"1e-6\""),
        Triple("05", Delimiter.COMMA, "\"05\""),
        Triple("true", Delimiter.COMMA, "\"true\""),
        Triple("false", Delimiter.COMMA, "\"false\""),
        Triple("null", Delimiter.COMMA, "\"null\""),
        Triple("", Delimiter.COMMA, "\"\""),
        Triple(" lead", Delimiter.COMMA, "\" lead\""),
        Triple("trail ", Delimiter.COMMA, "\"trail \""),
        Triple("-", Delimiter.COMMA, "\"-\""),
        Triple("-item", Delimiter.COMMA, "\"-item\""),
        Triple("k:v", Delimiter.COMMA, "\"k:v\""),
        Triple("[x]", Delimiter.COMMA, "\"[x]\""),
        Triple("{x}", Delimiter.COMMA, "\"{x}\""),
        Triple("say \"hi\"", Delimiter.COMMA, "\"say \\\"hi\\\"\""),
        Triple("C:\\dir", Delimiter.COMMA, "\"C:\\\\dir\""),
        Triple("line\nbreak", Delimiter.COMMA, "\"line\\nbreak\""),
        Triple("cr\rhere", Delimiter.COMMA, "\"cr\\rhere\""),
        Triple("tab\there", Delimiter.COMMA, "\"tab\\there\""),
        Triple("a,b", Delimiter.COMMA, "\"a,b\""),
        Triple("a|b", Delimiter.COMMA, "a|b"),
        Triple("a,b", Delimiter.PIPE, "a,b"),
        Triple("a|b", Delimiter.PIPE, "\"a|b\""),
        Triple("a,b", Delimiter.TAB, "a,b"),
        Triple("a|b", Delimiter.TAB, "a|b"),
        Triple("tab\there", Delimiter.TAB, "\"tab\\there\""),
    )

    @Test
    fun tableCellsAreQuotedOnlyWhenTheSpecRequiresIt() {
        for ((value, delimiter, cell) in cellCases) {
            val text = Toon.encode(row("t" to listOf(row("id" to 1L, "v" to value))), EncodeOptions(delimiter = delimiter))
            val written = text.lines()[1].trim().substringAfter(delimiter.symbol)
            assertEquals(cell, written, "value=${value.debug()} delimiter=$delimiter")
            val back = (Toon.decode(text) as Map<*, *>)["t"] as List<*>
            assertEquals(value, (back.single() as Map<*, *>)["v"], "round-trip of ${value.debug()} with $delimiter")
        }
    }

    @Test
    fun singleColumnTableRowsRoundTrip() {
        val failures = cellCases.mapNotNull { (value, delimiter, _) ->
            val data = row("t" to listOf(row("v" to value), row("v" to "ok")))
            val text = Toon.encode(data, EncodeOptions(delimiter = delimiter))
            val back = runCatching { Toon.decode(text) }
            if (back.getOrNull() == data) null else "${value.debug()} with $delimiter → ${text.debug()} → ${back.getOrElse { it.message }}"
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun inlineArrayValuesUseTheSameRulesAsCells() {
        for ((value, delimiter, cell) in cellCases) {
            val text = Toon.encode(row("xs" to listOf(value)), EncodeOptions(delimiter = delimiter))
            assertEquals(cell, text.substringAfter(": "), "value=${value.debug()} delimiter=$delimiter")
        }
    }

    @Test
    fun objectValuesUseTheDocumentDelimiter() {
        assertEquals("note: \"a,b\"", Toon.encode(row("note" to "a,b")))
        assertEquals("note: a,b", Toon.encode(row("note" to "a,b"), EncodeOptions(delimiter = Delimiter.PIPE)))
        assertEquals("note: \"a|b\"", Toon.encode(row("note" to "a|b"), EncodeOptions(delimiter = Delimiter.PIPE)))
        assertEquals("note: a|b", Toon.encode(row("note" to "a|b")))
    }

    @Test
    fun numbersAndLiteralsStayUnquoted() {
        val rows = listOf(row("n" to 42L, "d" to 12.5.toBigDecimal(), "b" to true, "z" to null))
        assertEquals("rows[1]{n,d,b,z}:\n  42,12.5,true,null", Toon.encode(row("rows" to rows)))
    }

    // ---------- Decoder output is gated by debug ----------

    private fun stdoutOf(block: () -> Unit): String {
        val captured = ByteArrayOutputStream()
        val original = System.out
        System.setOut(PrintStream(captured, true))
        try { block() } finally { System.setOut(original) }
        return captured.toString()
    }

    @Test
    fun decoderPrintsOnlyWithDebug() {
        val text = Toon.encode(row("rows" to listOf(row("a" to "x,y", "b" to 1L))))
        val lenientInput = "root:\n   child: 1"
        assertEquals("", stdoutOf { Toon.decode(text); Toon.decode(lenientInput, DecoderOptions(strict = false)) })
        assertTrue(stdoutOf { Toon.decode(text, DecoderOptions(debug = true)) }.isNotEmpty(), "debug = true must still print")
        assertTrue(
            stdoutOf { Toon.decode(lenientInput, DecoderOptions(strict = false, debug = true)) }.contains("WARN"),
            "lenient warnings must print with debug = true",
        )
    }

    // ---------- Seeded round-trip fuzz ----------

    private val alphabet = listOf(
        "a", "b", "é", " ", ",", "|", "\t", "\n", "\r", ":", "\"", "\\", "-", "#", "[", "]", "{", "}",
        "0", "1", "9", ".", "e", "E", "+", "true", "false", "null",
    )

    private fun randomString(random: Random): String =
        buildString { repeat(random.nextInt(0, 7)) { append(alphabet[random.nextInt(alphabet.size)]) } }

    @Test
    fun randomStringsRoundTripInCellsInlineArraysAndObjectValues() {
        val random = Random(20261014)
        val strings = List(1000) { randomString(random) }
        for (delimiter in Delimiter.entries) {
            val options = EncodeOptions(delimiter = delimiter)
            val failures = mutableListOf<String>()
            for (s in strings) {
                val inTable = row("t" to listOf(row("id" to 1L, "v" to s), row("id" to 2L, "v" to "ok")))
                val inline = row("xs" to listOf(s, "ok"))
                val inObject = row("v" to s, "w" to "ok")
                for (value in listOf(inTable, inline, inObject)) {
                    val text = Toon.encode(value, options)
                    val back = runCatching { Toon.decode(text) }
                    if (back.getOrNull() != value) failures += "${s.debug()} → ${text.debug()} → ${back.getOrElse { it.message }}"
                }
            }
            assertTrue(failures.isEmpty(), "delimiter=$delimiter, ${failures.size} failures, first ones:\n" + failures.take(10).joinToString("\n"))
        }
    }

    private fun String.debug() = "\"" + replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""
}
