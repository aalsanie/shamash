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

    // implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    maxHeapSize = "2g"
    // If migrated tests to JUnit5.
    // useJUnitPlatform()
}
