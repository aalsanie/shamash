plugins {
    `java-library`
    kotlin("jvm")
    id("org.jetbrains.kotlinx.kover")
    id("org.jetbrains.dokka")
    id("com.vanniktech.maven.publish")
}

description = "Shared findings, reports, and baseline contracts for Shamash."

kotlin {
    jvmToolchain(17)
}

repositories {
    mavenCentral()
}

dependencies {

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    maxHeapSize = "2g"
}
