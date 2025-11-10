plugins {
    kotlin("jvm") version "2.0.21"
    `maven-publish`
    signing
    id("io.codearte.nexus-staging") version "0.30.0"
    id("org.jetbrains.dokka") version "1.9.10"
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

tasks.register<Jar>("sourcesJar") {
    archiveClassifier.set("sources")
    from(sourceSets.main.get().allSource)
}

tasks.register<Jar>("javadocJar") {
    dependsOn("dokkaHtml")
    archiveClassifier.set("javadoc")
    from(buildDir.resolve("dokka"))
}

publishing {
    publications {
        create<MavenPublication>("toon") {
            from(components["kotlin"])
            artifact(tasks["sourcesJar"])
            artifact(tasks["javadocJar"])
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

signing {
    useGpgCmd()
    sign(publishing.publications["toon"])
}

nexusStaging {
    packageGroup = "br.com.vexpera"
    username = System.getenv("OSSRH_USERNAME")
    password = System.getenv("OSSRH_PASSWORD")
}