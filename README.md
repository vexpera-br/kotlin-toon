# 🧬 Kotlin TOON — Token-Oriented Object Notation

**TOON (Token-Oriented Object Notation)** is a lightweight, human-friendly data serialization format designed for concise, structured data — ideal for LLMs, configuration files, structured logs, and beyond.

**kotlin-toon** is a 100% Kotlin implementation — spec-compliant and ready for production.

Inspired by the readability of YAML and the tabular elegance of CSV.

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
