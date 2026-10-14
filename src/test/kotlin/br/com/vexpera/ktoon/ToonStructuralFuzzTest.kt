package br.com.vexpera.ktoon

import br.com.vexpera.ktoon.decoder.DecoderOptions
import java.math.BigDecimal
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Seeded structural fuzz: random nested values (objects, inline/tabular/list arrays, arrays of arrays, objects as
 * list items, empty containers) with tricky keys and strings must round-trip for every encoder option.
 */
class ToonStructuralFuzzTest {

    private val strings = listOf(
        "a", "Ada Lovelace", "", " x", "y ", "42", "-1.5", "05", "true", "null", "a,b", "a|b", "tab\there",
        "line\nbreak", "k:v", "-dash", "#hash", "[1]", "{x}", "quote\"s", "back\\slash", "ação", "- item",
    )
    private val keys = listOf("id", "name", "a.b", "_x", "full name", "order:id", "a,b", "a|b", "", "123", "-k", "#k", "q\"k", "x[1]", "ü")

    private fun primitive(r: Random): Any? = when (r.nextInt(6)) {
        0 -> null
        1 -> r.nextBoolean()
        2 -> r.nextLong(-1000, 1000)
        3 -> BigDecimal(r.nextInt(-9999, 9999)).movePointLeft(2).stripTrailingZeros().let { if (it.scale() <= 0) it.toLong() else it }
        else -> strings[r.nextInt(strings.size)]
    }

    private fun value(r: Random, depth: Int): Any? {
        if (depth >= 4) return primitive(r)
        return when (r.nextInt(7)) {
            0, 1 -> primitive(r)
            2, 3 -> obj(r, depth)
            4 -> List(r.nextInt(0, 4)) { primitive(r) }
            5 -> table(r)
            else -> List(r.nextInt(0, 4)) { value(r, depth + 1) }
        }
    }

    private fun obj(r: Random, depth: Int): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        repeat(r.nextInt(0, 4)) { m[keys[r.nextInt(keys.size)]] = value(r, depth + 1) }
        return m
    }

    private fun table(r: Random): List<Map<String, Any?>> {
        val cols = keys.shuffled(r).take(r.nextInt(1, 4))
        return List(r.nextInt(1, 4)) { cols.associateWith { primitive(r) } }
    }

    private fun same(a: Any?, b: Any?): Boolean = when {
        a is Map<*, *> && b is Map<*, *> -> a.keys.toList() == b.keys.toList() && a.keys.all { same(a[it], b[it]) }
        a is List<*> && b is List<*> -> a.size == b.size && a.indices.all { same(a[it], b[it]) }
        a is Number && b is Number -> BigDecimal(a.toString()).compareTo(BigDecimal(b.toString())) == 0
        else -> a == b
    }

    @Test
    fun randomNestedValuesRoundTripForEveryOption() {
        val r = Random(20261014)
        val values = List(600) { if (it % 5 == 0) List(r.nextInt(0, 4)) { value(r, 1) } else obj(r, 0) }
        val failures = mutableListOf<String>()
        for (delimiter in Delimiter.entries) for (marker in listOf(false, true)) for (indent in listOf(2, 4)) {
            val options = EncodeOptions(indent = indent, delimiter = delimiter, lengthMarker = marker)
            for (v in values) {
                val text = Toon.encode(v, options)
                val back = runCatching { Toon.decode(text, DecoderOptions(indentWidth = indent)) }
                if (back.isFailure || !same(v, back.getOrNull())) {
                    failures += "$options\n--- value: $v\n--- toon:\n$text\n--- got: ${back.getOrElse { "${it::class.simpleName}: ${it.message}" }}"
                }
            }
        }
        assertTrue(failures.isEmpty(), "${failures.size} failures; first:\n" + failures.take(3).joinToString("\n\n"))
    }
}
