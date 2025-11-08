@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package br.com.vexpera.ktoon

import br.com.vexpera.ktoon.decoder.DecoderOptions
import br.com.vexpera.ktoon.decoder.Decoder
import kotlin.reflect.KClass
import kotlin.reflect.full.primaryConstructor

/**
 * TOON – Token-Oriented Object Notation (JVM core)
 * Conformante à TOON Spec v1.4 (Johann Schopplich)
 *
 * - Objetos por indentação (key: value / key:)
 * - Arrays tabulares: key[#?N<delim?>]{f1<delim>f2}: linhas com <delim>
 * - Arrays inline de primitivos: key[#?N<delim?>]: v1<delim>v2...
 * - Strict mode valida indentação, tabs, contagens, larguras e linhas em branco
 * - Lenient mode relaxa indentação e ignora blanks internos em tabelas
 */
object Toon {
    fun encode(value: Any?, options: EncodeOptions = EncodeOptions()): String =
        Encoder(options).encode(value)

    fun decode(input: String, options: DecoderOptions = DecoderOptions()): Any? =
        Decoder(options).decode(input)
}

enum class Delimiter(val symbol: String) { COMMA(","), TAB("\t"), PIPE("|") }

// ---------- Utility ----------

class ToonParseException(message: String) : RuntimeException(message)

private fun firstUnquotedIndexOf(text: String, ch: Char): Int {
    var inQuotes = false
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c == '\\' && i + 1 < text.length) { i += 2; continue }
        if (c == '"') { inQuotes = !inQuotes; i++; continue }
        if (!inQuotes && c == ch) return i
        i++
    }
    return -1
}

// ---------- Kotlin idiomatic layer ----------

class ToonNode(val value: Any?) {

    fun asMap(): Map<String, ToonNode> = when (value) {
        is Map<*, *> -> value.entries.associate { (k, v) -> k.toString() to ToonNode(v) }
        else -> emptyMap()
    }

    inline fun <reified T : Any> asListOf(): List<T> {
        val listValue = when (value) {
            is List<*> -> value
            is Map<*, *> -> {
                val onlyValue = (value as Map<*, *>).values.singleOrNull()
                if (onlyValue is List<*>) onlyValue else return emptyList()
            }
            else -> return emptyList()
        }
        return listValue.map { ToonNode(it).asObject<T>() }
    }

    fun asList(): List<ToonNode> = when (value) {
        is List<*> -> value.map { ToonNode(it) }
        else -> emptyList()
    }

    fun asString(): String? = value as? String ?: value?.toString()
    fun asInt(): Int? = (value as? Number)?.toInt()
    fun asLong(): Long? = (value as? Number)?.toLong()
    fun asDouble(): Double? = (value as? Number)?.toDouble()
    fun asBoolean(): Boolean? = value as? Boolean

    inline fun <reified T : Any> asObject(): T {
        val map = value as? Map<*, *> ?: throw ToonParseException("Expected map for ${T::class.simpleName}")
        return construct(T::class, map)
    }

    operator fun get(key: String): ToonNode? =
        (value as? Map<*, *>)?.get(key)?.let { ToonNode(it) }

    fun <T : Any> construct(clazz: KClass<T>, map: Map<*, *>): T {
        val ctor = clazz.primaryConstructor
            ?: throw ToonParseException("No primary constructor for ${clazz.simpleName}")
        val args = ctor.parameters.associateWith { param ->
            val raw = map[param.name]
            when (param.type.classifier) {
                String::class -> raw?.toString()
                Int::class -> (raw as? Number)?.toInt()
                Long::class -> (raw as? Number)?.toLong()
                Double::class -> (raw as? Number)?.toDouble()
                Boolean::class -> raw as? Boolean
                else -> raw
            }
        }
        return ctor.callBy(args)
    }
}

fun toon(text: String): ToonNode = ToonNode(Toon.decode(text))
inline fun <reified T : Any> toon(text: String, block: ToonNode.() -> T): T {
    val root = ToonNode(Toon.decode(text))
    return root.block()
}
