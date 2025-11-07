# 🧬 Kotlin TOON — Token-Oriented Object Notation

**TOON (Token-Oriented Object Notation)** is a lightweight, human-readable data serialization format designed for shorten data exchanges, specially for LLMs.
It’s inspired by YAML’s indentation and CSV’s tabular simplicity — ideal for configuration files, structured logs, or data exchange between systems.

---

## ✨ Why TOON?

TOON was built to be **simple for humans**, **robust for machines**, and **idiomatic for Kotlin**:

| Feature                       | Description                                         |
| ----------------------------- | --------------------------------------------------- |
| ✅ Indentation-based hierarchy | Uses spaces to define structure (like YAML)         |
| ✅ Table syntax                | `users[3]{id,name,role}:` for compact tabular data  |
| ✅ Fully typed API             | Converts directly into `data class` objects         |
| ✅ No dependencies             | Pure Kotlin, no third-party libraries               |
| ✅ Reversible encoding         | `decode → encode → decode` is lossless              |
| ✅ Performance-tuned           | Linear complexity, optimized for JVM and KMP (soon) |

---

## 🚀 Installation

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("br.com.vexpera:kotlin-toon:1.0.0")
}
```

---

## 🧉 Quick Start

### Example 1: Simple object

**TOON file**

```toon
user:
  name: "Alice"
  age: 27
  active: true
```

**Kotlin code**

```kotlin
import br.com.vexpera.ktoon.*

data class User(val name: String, val age: Int, val active: Boolean)

val text = """
user:
  name: "Alice"
  age: 27
  active: true
""".trimIndent()

val root = toon(text)
val user = root["user"]!!.asObject<User>()

println(user.name)  // Alice
println(user.age)   // 27
```

---

### Example 2: Nested tables

```toon
project:
  name: "Dream Engine"
  authors[3]{id,name,role}:
    1,Cláudio Marcelo Silva,lead
    2,Jane Doe,artist
    3,John Smith,engineer
  assets[2]{id,path,size}:
    1,"/models/player.obj",2048
    2,"/textures/sky.png",1024
```

**Parsing tables into data classes:**

```kotlin
data class Author(val id: Int, val name: String, val role: String)
data class Asset(val id: Int, val path: String, val size: Int)

val project = toon(text)["project"]!!
val authors = project["authors"]!!.asListOf<Author>()
val assets  = project["assets"]!!.asListOf<Asset>()

println(authors.first().name)  // Cláudio Marcelo Silva
println(assets.last().path)    // /textures/sky.png
```

---

### Example 3: Encoding Kotlin objects back to TOON

```kotlin
val data = mapOf(
    "users" to listOf(
        mapOf("id" to 1, "name" to "Alice"),
        mapOf("id" to 2, "name" to "Bob")
    )
)

val text = Toon.encode(data)
println(text)
```

Output:

```toon
users[2]{id,name}:
  1,Alice
  2,Bob
```

---

## 🧠 DSL Helpers

You can work directly with the `ToonNode` DSL:

```kotlin
val version = toon(text)["project"]?.get("version")?.asInt()
val authors: List<Author> = toon(text) { asListOf<Author>() }
```

---

## 🤪 Tests

This library includes a full test suite covering:

| Test              | Purpose                                  |
| ----------------- | ---------------------------------------- |
| `ToonSmokeTest`   | Basic syntax and simple decoding         |
| `ToonComplexTest` | Deeply nested structures, tables, arrays |
| `PerformanceTest` | Measures encode/decode throughput        |
| `RoundtripTest`   | Verifies encoding-decoding symmetry      |

Run locally:

```bash
./gradlew test
```

---

## ⚡ Performance

| Operation                 | File            | Time   | Throughput     |
| ------------------------- | --------------- | ------ | -------------- |
| **Decode (human text)**   | 1 KB / 45 lines | ~39 ms | ~1,100 lines/s |
| **Encode (machine text)** | 1 KB / 45 lines | ~8 ms  | ~5,000 lines/s |
| **Roundtrip Decode**      | 1 KB / 45 lines | ~5 ms  | ~8,800 lines/s |

Linear complexity — scales proportionally with file size.

---

## ⚙️ Advanced Options

```kotlin
val decoded = Toon.decode(text, DecodeOptions(strict = false))
val encoded = Toon.encode(data, EncodeOptions(indent = 4, lengthMarker = true))
```

* `strict`: allows irregular indentation
* `lengthMarker`: adds `[n]` markers for array sizes in headers
* `delimiter`: choose between `,`, `|`, or `\t`

---

## 🔜 Supported Types

* Scalars (`String`, `Boolean`, `Number`, `null`)
* Nested objects (`Map<String, Any?>`)
* Lists and tables (`List<Map<String, Any?>>`)
* Data classes (via reflection)
* Escaped strings (`"Line 1\nLine 2"`)
* Inline CSV tables

---

## 🧰 License

MIT License © 2025 [Cláudio Marcelo Silva](https://github.com/claudiomarcelo)

---

## 📦 Maven Central

**Gradle (Kotlin DSL):**

```kotlin
implementation("br.com.vexpera:kotlin-toon:1.0.0")
```

**Maven (XML):**

```xml
<dependency>
  <groupId>br.com.vexpera</groupId>
  <artifactId>kotlin-toon</artifactId>
  <version>1.0.0</version>
</dependency>
```

---

## 🔗 Links

* 📘 Documentation: [https://github.com/vexpera/kotlin-toon](https://github.com/vexpera/kotlin-toon)
* 🧩 Issue Tracker: [https://github.com/vexpera/kotlin-toon/issues](https://github.com/vexpera/kotlin-toon/issues)
