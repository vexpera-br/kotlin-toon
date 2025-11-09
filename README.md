# 🧬 Kotlin TOON — Token-Oriented Object Notation

**TOON (Token-Oriented Object Notation)** is a lightweight, human-friendly data serialization format designed for concise, structured data — ideal for LLMs, configuration files, structured logs, and beyond.

**kotlin-toon** is a 100% Kotlin implementation — spec-compliant and ready for production. Based on [the original spec from Johann Schopplich](https://github.com/toon-format/spec/blob/main/SPEC.md): 

TOON is inspired by the readability of YAML and the tabular elegance of CSV, and specially useful to provide structured data for LLM APIs (because a verbose protocol generates more tokens, and tokens can be expensive).

---

## ✨ Why TOON?

| Feature                       | Description                                         |
| ----------------------------- | --------------------------------------------------- |
| ✅ Indentation-based hierarchy | Uses spaces to define structure (like YAML)         |
| ✅ Table syntax                | `users[3]{id,name}` for compact tabular records     |
| ✅ Fully typed API             | Converts directly into Kotlin `data class`es        |
| ✅ No dependencies             | Pure Kotlin, zero external libraries                |
| ✅ Reversible encoding         | `decode → encode → decode` is lossless              |
| ✅ Performance-optimized       | Fast, linear parsing — ideal for JVM & Kotlin Multiplatform (soon) |
| ✅ Spec-conformant             | Fully compliant with official TOON specification    |

---

## 🚀 Installation

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("br.com.vexpera:kotlin-toon:1.0.0")
}
