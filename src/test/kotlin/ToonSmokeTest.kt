import br.com.vexpera.ktoon.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

data class User(val id: Int, val name: String, val role: String)

class ToonSmokeTest {

    private fun readToon(name: String): String {
        val url = this::class.java.classLoader.getResource("$name.toon")
            ?: error("Resource not found: $name.toon")
        return url.readText().trimEnd()
    }

    @Test
    fun decodeSimpleTable() {
        val text = readToon("simple_table")
        val users = toon(text) { asListOf<User>() }

        assertEquals(2, users.size)
        assertEquals("Alice", users[0].name)
        assertEquals("user", users[1].role)
    }

    @Test
    fun decodeNestedObject() {
        val text = readToon("nested_object")

        val root = toon(text)
        val app = root["app"]
        assertNotNull(app)
        val version = app["version"]?.asInt()
        val users: List<User> = app["users"]?.asListOf<User>().orEmpty()

        assertEquals(1, version)
        assertEquals(2, users.size)
        assertEquals("Bob", users[1].name)
    }

    @Test
    fun scalarAndNestedBlocks() {
        val text = readToon("scalar_block")

        val root = toon(text)
        val cfg = root["config"]
        assertNotNull(cfg)
        assertEquals("My App", cfg["title"]?.asString())
        assertEquals(true, cfg["debug"]?.asBoolean())
        assertEquals(3, cfg["limits"]?.get("retries")?.asInt())
        assertEquals(5.5, cfg["limits"]?.get("timeout")?.asDouble())
    }

    @Test
    fun encodeAndDecodeRoundtrip() {
        val text = readToon("roundtrip_users")
        val users = toon(text) { asListOf<User>() }

        val encoded = Toon.encode(users.map {
            mapOf("id" to it.id, "name" to it.name, "role" to it.role)
        }, EncodeOptions(lengthMarker = true))

        val decoded = toon(encoded) { asListOf<User>() }

        assertEquals(users.size, decoded.size)
        assertEquals(users[0].name, decoded[0].name)
        assertEquals(users[1].role, decoded[1].role)
    }
}