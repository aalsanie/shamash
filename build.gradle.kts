/*
 * Shamash is a JVM architecture enforcement tool that helps teams
 * define, validate, and continuously enforce architectural boundaries.
 *
 * Copyright © 2025-2026 | Author: @aalsanie
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import com.diffplug.gradle.spotless.SpotlessExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    kotlin("jvm") version "1.9.23" apply false
    id("org.jetbrains.intellij.platform") version "2.10.5" apply false

    id("com.diffplug.spotless") version "8.1.0" apply false
    id("org.jetbrains.kotlinx.kover") version "0.9.4" apply false
}

allprojects {
    group = "io.shamash"
    version = "0.70.1"

    repositories {
        mavenCentral()
    }
}

subprojects {
    // Default Kotlin/JVM behavior for all modules (safe even if some modules don't compile Kotlin sources)
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

    // Spotless
    apply(plugin = "com.diffplug.spotless")
    plugins.withId("com.diffplug.spotless") {
        extensions.configure<SpotlessExtension> {
            kotlin {
                ktlint()
                licenseHeaderFile(rootProject.file("spotless/HEADER.kt"), "package ")
            }
            kotlinGradle {
                ktlint()
            }
        }
    }

    // Kover (optional): modules can override
    apply(plugin = "org.jetbrains.kotlinx.kover")
}