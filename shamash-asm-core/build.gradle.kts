plugins {
    `java-library`
    kotlin("jvm")
    id("org.jetbrains.kotlinx.kover")
    id("org.jetbrains.dokka")
    id("com.vanniktech.maven.publish")
}

description = "Standalone JVM bytecode architecture analysis and rule registry API for Shamash."

kotlin {
    jvmToolchain(17)
}

repositories {
    mavenCentral()
}

dependencies {
    api(project(":shamash-artifacts"))
    implementation(project(":shamash-export"))

    api("org.ow2.asm:asm:9.10.1")
    implementation("org.ow2.asm:asm-util:9.10.1")

    implementation("com.networknt:json-schema-validator:1.5.9")
    api("com.fasterxml.jackson.core:jackson-databind:2.22.1")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.22.2")
    implementation("org.snakeyaml:snakeyaml-engine:3.1.1")

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    maxHeapSize = "2g"
}
