plugins {
    `java-library`
    kotlin("jvm")
    id("org.jetbrains.kotlinx.kover")
    id("org.jetbrains.dokka")
    id("com.vanniktech.maven.publish")
}

description = "JSON, SARIF, HTML, and XML report exporters for Shamash."

kotlin {
    jvmToolchain(17)
}

repositories {
    mavenCentral()
}

dependencies {
    api(project(":shamash-artifacts"))

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    maxHeapSize = "2g"
}
