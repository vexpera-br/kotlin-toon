# 🧬 Kotlin TOON — Token-Oriented Object Notation

**TOON (Token-Oriented Object Notation)** is a lightweight, human-friendly data serialization format designed for concise, structured data — ideal for LLMs, configuration files, structured logs, and beyond.

**kotlin-toon** is a 100% Kotlin implementation — spec-compliant and ready for production. Based on [the original spec from Johann Schopplich](https://github.com/toon-format/spec/blob/v1.4.0/SPEC.md) (v1.4): 


TOON is inspired by the readability of YAML and the tabular elegance of CSV, and specially useful to provide structured data for LLM APIs (because a verbose protocol generates more tokens, and tokens can be expensive).

---

## ✨ Why TOON?

| Feature                       | Description                                         |
| ----------------------------- | --------------------------------------------------- |
| ✅ Indentation-based hierarchy | Uses spaces to define structure (like YAML)         |
| ✅ Table syntax                | `users[3]{id,name}` for compact tabular records     |
| ✅ Fully typed API             | Converts directly into Kotlin `data class`es (soon) |
| ✅ No dependencies             | Pure Kotlin, zero external libraries                |
| ✅ Reversible encoding         | `decode → encode → decode` is lossless              |
| ✅ Performance-optimized       | Fast, linear parsing — ideal for JVM & Kotlin Multiplatform (soon) |
| ✅ Spec-conformant             | Passes all 323 official conformance fixtures of TOON spec v1.4.0 |

---

## 🚀 Installation

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("br.com.vexpera:kotlin-toon:1.0.0")
}
````

---

## 🧉 Quick Start

### Example 1: Simple object

```toon
user:
  name: "Alice"
  age: 27
  active: true
```

```kotlin
data class User(val name: String, val age: Int, val active: Boolean)

val text = """ user:\n  name: "Alice"\n  age: 27\n  active: true """.trimIndent()

val root = toon(text)
val user = root["user"]!!.asObject<User>()
```

---

### Example 2: Nested tabular data

```toon
project:
  name: "Dream Engine"
  authors[3]{id,name,role}:
    1,Cláudio Marcelo Silva,lead
    2,Jane Doe,artist
    3,John Smith,engineer
```

```kotlin
data class Author(val id: Int, val name: String, val role: String)

val authors = toon(text)["project"]?.get("authors")?.asListOf<Author>()
```

---

### Example 3: Encoding TOON

```kotlin
val data = mapOf(
    "users" to listOf(
        mapOf("id" to 1, "name" to "Alice"),
        mapOf("id" to 2, "name" to "Bob")
    )
)

println(Toon.encode(data))
```

```toon
users[2]{id,name}:
  1,Alice
  2,Bob
```

---

## ⚙️ Advanced Options

```kotlin
val decoded = Toon.decode(text, DecodeOptions(strict = false))
val encoded = Toon.encode(data, EncodeOptions(indent = 4, lengthMarker = true))
```

| Option         | Description                                  |                                 |
| -------------- | -------------------------------------------- | ------------------------------- |
| `strict`       | Enables strict indentation & structure rules |                                 |
| `lengthMarker` | Adds `[n]` to headers to enforce row count   |                                 |
| `delimiter`    | Use `,`, `                                   | `, or `\t` for table separation |

---

## 🧠 Kotlin DSL

```kotlin
val name = toon(text)["user"]?.get("name")?.asString()
val users: List<User> = toon(text) { asListOf<User>() }
```

---

## 🧪 Test Suite

Fully tested with:

* Conformance to official spec
* Round-trip integrity
* Tabular edge cases
* Lenient and strict modes

Run tests:

```bash
./gradlew test
```

---

## 📦 Maven Central

**Gradle:**

```kotlin
implementation("br.com.vexpera:kotlin-toon:1.0.0")
```

**Maven:**

```xml
<dependency>
  <groupId>br.com.vexpera</groupId>
  <artifactId>kotlin-toon</artifactId>
  <version>1.0.0</version>
</dependency>
```

---

## 🚀 Releasing

Releases go to Maven Central through the [Sonatype Central Portal](https://central.sonatype.com/), with the
[gradle-maven-publish-plugin](https://vanniktech.github.io/gradle-maven-publish-plugin/central/).

1. Set `version` in `build.gradle.kts`.
2. Provide the credentials outside the repository, in `~/.gradle/gradle.properties` or as `ORG_GRADLE_PROJECT_*`
   environment variables:
   - `mavenCentralUsername` / `mavenCentralPassword`: a Central Portal **user token**, not the login password;
   - signing: `signingInMemoryKey` (+ `signingInMemoryKeyPassword`), or `signing.keyId` / `signing.password` /
     `signing.secretKeyRingFile`, or `signing.gnupg.keyName` to sign through the local gpg agent.
3. Run `./gradlew publishToMavenCentral`, then press **Publish** on the deployment at
   <https://central.sonatype.com/publishing/deployments> (or run `./gradlew publishAndReleaseToMavenCentral` to do both).

## COMMING SOON:

* _Path expansion_ and _key folding_ (new in spec 1,5)
* KMP full support (JVM, Android, iOS, KotlinJS, Native)
* kotlinx.serialization support

## 📚 Resources

* [Specification (v1.4)](https://github.com/toon-format/spec/blob/v1.4.0/SPEC.md)
* [Issue Tracker](https://github.com/vexpera-br/kotlin-toon/issues)
* [Official TOON format](https://github.com/toon-data/toon)

---

## 🧰 License

MIT © 2025 [Cláudio Marcelo Silva](https://github.com/claudiomarcelo)
---
