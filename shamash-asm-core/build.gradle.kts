plugins {
    kotlin("jvm")
    id("org.jetbrains.kotlinx.kover")
}

kotlin {
    jvmToolchain(17)
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":shamash-artifacts"))
    implementation(project(":shamash-export"))

    // ASM engine deps
    implementation("org.ow2.asm:asm:9.7.1")
    implementation("org.ow2.asm:asm-util:9.7.1")

    // Validation
    implementation("com.networknt:json-schema-validator:1.5.9")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.21.0")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.2")
    implementation("org.snakeyaml:snakeyaml-engine:2.7")

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    maxHeapSize = "2g"
    // If migrated tests to JUnit5.
    // useJUnitPlatform()
}
