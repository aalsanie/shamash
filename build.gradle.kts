import com.diffplug.gradle.spotless.SpotlessExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    kotlin("jvm") version "2.4.10" apply false
    id("org.jetbrains.intellij.platform") version "2.10.5" apply false
    id("com.diffplug.spotless") version "8.9.0" apply false
    id("org.jetbrains.kotlinx.kover") version "0.9.9" apply false
}

allprojects {
    group = "io.shamash"
    version = "0.91.0"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<KotlinJvmProjectExtension>("kotlin") {
            jvmToolchain(17)
        }
        dependencies.apply {
            add("testImplementation", kotlin("test"))
            add("testImplementation", "junit:junit:4.13.2")
        }
        tasks.withType(Test::class.java).configureEach {
            maxHeapSize = "2g"
            useJUnitPlatform()
        }
    }

    apply(plugin = "com.diffplug.spotless")
    plugins.withId("com.diffplug.spotless") {
        extensions.configure<SpotlessExtension> {
            kotlin {
                ktlint()
                licenseHeaderFile(rootProject.file("spotless/HEADER.kt"), "package ")
            }
            kotlinGradle { ktlint() }
        }
    }

    apply(plugin = "org.jetbrains.kotlinx.kover")
}
