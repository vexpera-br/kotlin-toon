plugins {
    kotlin("jvm") version "2.0.21"
    `maven-publish`
}

group = "br.com.vexpera"
version = "1.0.0"
description = "TOON – Token-Oriented Object Notation for Kotlin (JVM core)"

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("reflect"))
    testImplementation(kotlin("test"))
}

publishing {
    publications {
        create<MavenPublication>("toon") {
            from(components["kotlin"])
            groupId = "br.com.vexpera"
            artifactId = "kotlin-toon"
            version = "1.0.0"

            pom {
                name.set("kotlin-toon")
                description.set("TOON – a minimal human-readable serialization format for Kotlin")
                url.set("https://github.com/vexpera/kotlin-toon")
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
                    connection.set("scm:git:https://github.com/vexpera/kotlin-toon.git")
                    developerConnection.set("scm:git:ssh://github.com:vexpera/kotlin-toon.git")
                    url.set("https://github.com/vexpera/kotlin-toon")
                }
            }
        }
    }

    repositories {
        maven {
            name = "OSSRH"
            url = uri("https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/")
            credentials {
                username = System.getenv("OSSRH_USERNAME")
                password = System.getenv("OSSRH_PASSWORD")
            }
        }
    }
}
