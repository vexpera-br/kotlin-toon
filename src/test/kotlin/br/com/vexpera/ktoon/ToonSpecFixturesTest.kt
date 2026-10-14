package br.com.vexpera.ktoon

import br.com.vexpera.ktoon.decoder.DecoderOptions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.fail

/**
 * Runs the official, language-agnostic conformance fixtures of the TOON spec v1.4.0
 * (src/test/resources/spec-fixtures/v1.4.0, copied unchanged from toon-format/spec).
 *
 * Every case must pass, except the ones listed in known-failures.txt, which must still fail:
 * fixing one of them makes this test fail until its line is removed, so the list never goes stale.
 */
class ToonSpecFixturesTest {

    private data class Case(val id: String, val category: String, val test: JsonObject)

    private sealed interface Outcome {
        data object Pass : Outcome
        data class Fail(val why: String) : Outcome
    }

    private val fixtureFiles = listOf(
        "encode/primitives", "encode/objects", "encode/arrays-primitive", "encode/arrays-tabular",
        "encode/arrays-nested", "encode/arrays-objects", "encode/delimiters", "encode/whitespace", "encode/options",
        "decode/primitives", "decode/numbers", "decode/objects", "decode/arrays-primitive", "decode/arrays-tabular",
        "decode/arrays-nested", "decode/delimiters", "decode/whitespace", "decode/root-form",
        "decode/validation-errors", "decode/indentation-errors", "decode/blank-lines",
    )

    private fun resource(path: String): String =
        this::class.java.classLoader.getResource(path)?.readText() ?: error("Resource not found: $path")

    private fun cases(): List<Case> = fixtureFiles.flatMap { file ->
        val root = Json.parseToJsonElement(resource("spec-fixtures/v1.4.0/$file.json")).jsonObject
        val category = root.getValue("category").jsonPrimitive.content
        root.getValue("tests").jsonArray.map { t ->
            Case("$file :: ${t.jsonObject.getValue("name").jsonPrimitive.content}", category, t.jsonObject)
        }
    }

    private fun knownFailures(): Map<String, String> =
        resource("spec-fixtures/known-failures.txt").lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .associate { line ->
                val reason = line.substringAfter(" # ", missingDelimiterValue = "")
                require(reason.isNotBlank()) { "known-failures.txt: every line needs ' # <reason>': $line" }
                line.substringBefore(" # ").trim() to reason.trim()
            }

    @Test
    fun officialFixturesPassExceptKnownFailures() {
        val all = cases()
        val known = knownFailures()
        val ids = all.map { it.id }.toSet()
        val stale = known.keys - ids
        val unexpected = mutableListOf<String>()
        val nowPassing = mutableListOf<String>()
        var passed = 0

        for (case in all) {
            val outcome = run(case)
            when {
                outcome is Outcome.Pass && case.id in known -> nowPassing += case.id
                outcome is Outcome.Pass -> passed++
                outcome is Outcome.Fail && case.id !in known -> unexpected += "${case.id}\n      ${outcome.why}"
            }
        }

        println("TOON spec v1.4.0 fixtures: ${all.size} cases, $passed passed, ${known.size} known failures")
        val problems = buildList {
            if (unexpected.isNotEmpty()) add("Failing cases not in known-failures.txt:\n  - " + unexpected.joinToString("\n  - "))
            if (nowPassing.isNotEmpty()) add("Now passing, remove from known-failures.txt:\n  - " + nowPassing.joinToString("\n  - "))
            if (stale.isNotEmpty()) add("known-failures.txt names cases that do not exist:\n  - " + stale.joinToString("\n  - "))
        }
        if (problems.isNotEmpty()) fail(problems.joinToString("\n\n"))
    }

    private fun run(case: Case): Outcome = try {
        when (case.category) {
            "encode" -> runEncode(case.test)
            "decode" -> runDecode(case.test)
            else -> Outcome.Fail("unknown category ${case.category}")
        }
    } catch (e: Exception) {
        Outcome.Fail("${e::class.simpleName}: ${e.message}")
    }

    private fun runEncode(t: JsonObject): Outcome {
        val opts = t["options"]?.jsonObject
        val options = EncodeOptions(
            indent = opts?.get("indent")?.jsonPrimitive?.intOrNull ?: 2,
            delimiter = when (opts?.get("delimiter")?.jsonPrimitive?.content) {
                null, "," -> Delimiter.COMMA
                "\t" -> Delimiter.TAB
                "|" -> Delimiter.PIPE
                else -> return Outcome.Fail("unsupported delimiter option")
            },
            lengthMarker = opts?.get("lengthMarker")?.jsonPrimitive?.content == "#",
        )
        val expected = t.getValue("expected").jsonPrimitive.content
        val actual = Toon.encode(toKotlin(t.getValue("input")), options)
        return if (actual == expected) Outcome.Pass else Outcome.Fail("expected ${show(expected)} but was ${show(actual)}")
    }

    private fun runDecode(t: JsonObject): Outcome {
        val opts = t["options"]?.jsonObject
        val options = DecoderOptions(
            strict = opts?.get("strict")?.jsonPrimitive?.booleanOrNull ?: true,
            indentWidth = opts?.get("indent")?.jsonPrimitive?.intOrNull ?: 2,
        )
        val input = t.getValue("input").jsonPrimitive.content
        val shouldError = t["shouldError"]?.jsonPrimitive?.booleanOrNull ?: false
        val result = runCatching { Toon.decode(input, options) }
        if (shouldError) {
            return if (result.isFailure) Outcome.Pass else Outcome.Fail("expected an error but decoded ${result.getOrNull()}")
        }
        val actual = result.getOrElse { return Outcome.Fail("${it::class.simpleName}: ${it.message}") }
        val expected = toKotlin(t.getValue("expected"))
        return if (sameValue(expected, actual)) Outcome.Pass else Outcome.Fail("expected $expected but was $actual")
    }

    /** JSON → the plain values the library works with: ordered maps, lists, String, Long/BigDecimal, Boolean, null. */
    private fun toKotlin(e: JsonElement): Any? = when (e) {
        is JsonNull -> null
        is JsonObject -> e.entries.associateTo(LinkedHashMap()) { (k, v) -> k to toKotlin(v) }
        is JsonArray -> e.map { toKotlin(it) }
        is JsonPrimitive -> when {
            e.isString -> e.content
            e.booleanOrNull != null -> e.booleanOrNull
            else -> e.contentOrNull!!.let { it.toLongOrNull() ?: BigDecimal(it) }
        }
    }

    /** Structural equality with key order, list order and value types; numbers compare by value. */
    private fun sameValue(a: Any?, b: Any?): Boolean = when {
        a is Map<*, *> && b is Map<*, *> ->
            a.keys.toList() == b.keys.toList() && a.keys.all { sameValue(a[it], b[it]) }
        a is List<*> && b is List<*> -> a.size == b.size && a.indices.all { sameValue(a[it], b[it]) }
        a is Number && b is Number -> BigDecimal(a.toString()).compareTo(BigDecimal(b.toString())) == 0
        else -> a == b
    }

    private fun show(s: String) = "\"" + s.replace("\n", "\\n").replace("\t", "\\t") + "\""
}
