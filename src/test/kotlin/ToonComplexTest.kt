import br.com.vexpera.ktoon.*
import kotlin.test.*

data class Author(val id: Int, val name: String, val role: String)
data class Asset(val id: Int, val type: String, val path: String, val size: Int)
data class Flag(val key: String, val value: String)
data class Log(val level: String, val message: String)

class ToonComplexTest {

    private fun readToon(name: String): String {
        val url = this::class.java.classLoader.getResource("$name.toon")
            ?: error("Resource not found: $name.toon")
        return url.readText().trimEnd()
    }

    private fun logMetric(label: String, startNs: Long, endNs: Long, lines: Int, size: Int) {
        val elapsedMs = (endNs - startNs) / 1_000_000.0
        val linesPerSec = (lines / (elapsedMs / 1000)).toInt()
        val kb = size / 1024.0
        println("📊 $label → ${"%.2f".format(elapsedMs)} ms, $lines lines, ${"%.2f".format(kb)} KB, ~${linesPerSec} lines/s")
    }

    @Test
    fun parseComplexScene_withPerformanceMetrics() {
        val text = readToon("complex_scene")
        val lineCount = text.lines().size
        val sizeBytes = text.toByteArray().size

        // --- Decode ---
        val t1 = System.nanoTime()
        val root = toon(text)
        val t2 = System.nanoTime()
        logMetric("Decode", t1, t2, lineCount, sizeBytes)

        // Validate correctness minimally
        val project = root["project"]!!
        assertEquals("Dream Engine", project["name"]?.asString())
        assertEquals(3, project["version"]?.asInt())

        // --- Encode ---
        val t3 = System.nanoTime()
        val encoded = Toon.encode(root.value)
        val t4 = System.nanoTime()
        logMetric("Encode", t3, t4, lineCount, sizeBytes)

        // --- Roundtrip ---
        val t5 = System.nanoTime()
        val roundtrip = Toon.decode(encoded)
        val t6 = System.nanoTime()
        logMetric("Roundtrip Decode", t5, t6, encoded.lines().size, encoded.toByteArray().size)

        // --- Consistency ---
        assertNotNull(roundtrip)
        val roundtripText = Toon.encode(roundtrip)
        assertTrue(roundtripText.contains("Dream Engine"))
        assertTrue(roundtripText.contains("authors"))
        println("✅ Roundtrip content verified (${roundtripText.length} chars)")
    }
}
