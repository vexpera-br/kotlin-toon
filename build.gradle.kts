import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm

plugins {
    kotlin("jvm") version "2.0.21"
    id("org.jetbrains.dokka") version "1.9.10"
    signing
    // Publishes to the Sonatype Central Portal (OSSRH / s01.oss.sonatype.org was shut down).
    // 0.35.0 is the last release that supports Kotlin 2.0.x, Gradle 8.13 and Dokka v1 (0.36.0 raised all three).
    id("com.vanniktech.maven.publish") version "0.35.0"
}

group = "br.com.vexpera"
version = "1.0.1"
description = "TOON – Token-Oriented Object Notation for Kotlin (JVM core)"

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("reflect"))
    testImplementation(kotlin("test"))
    // Test-only: reads the JSON conformance fixtures of the TOON spec.
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}

mavenPublishing {
    configure(KotlinJvm(javadocJar = JavadocJar.Dokka("dokkaHtml"), sourcesJar = true))
    publishToMavenCentral()
    signAllPublications()

    coordinates("br.com.vexpera", "kotlin-toon", version.toString())

    pom {
        name.set("kotlin-toon")
        description.set("TOON – a minimal human-readable serialization format for Kotlin")
        url.set("https://github.com/vexpera-br/kotlin-toon")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
            }
        }
        developers {
            developer {
                id.set("claudiomarcelo")
                name.set("Cláudio Marcelo Silva")
                email.set("vexpera.br@gmail.com")
            }
        }
        scm {
            connection.set("scm:git:https://github.com/vexpera-br/kotlin-toon.git")
            developerConnection.set("scm:git:ssh://git@github.com/vexpera-br/kotlin-toon.git")
            url.set("https://github.com/vexpera-br/kotlin-toon")
        }
    }
}

// Signing: an in-memory key (signingInMemoryKey*) or a keyring (signing.keyId / signing.secretKeyRingFile) works out of
// the box. To keep signing through the local gpg agent, as releases did before, set signing.gnupg.keyName.
if (providers.gradleProperty("signing.gnupg.keyName").isPresent) {
    signing { useGpgCmd() }
}
