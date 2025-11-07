// build.gradle.kts
plugins { kotlin("jvm") version "1.9.24" }

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("reflect"))
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }

kotlin {
    jvmToolchain(17)
}
