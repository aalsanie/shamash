/*
 *
 * shamash is an architectural refactoring tool that enforces clean architecture.
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

import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    kotlin("jvm") version "1.9.23"
    id("org.jetbrains.intellij.platform") version "2.10.5"
    id("com.diffplug.spotless") version "8.1.0"
    id("org.jetbrains.kotlinx.kover") version "0.9.4"
}

group = "io.shamash"
version = "0.50.0"

kotlin {
    jvmToolchain(17)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation("org.ow2.asm:asm:9.7.1")
    implementation("org.ow2.asm:asm-util:9.7.1")
    implementation("com.networknt:json-schema-validator:1.5.9")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")

    intellijPlatform {
        intellijIdea("2024.2")

        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.kotlin")

        testFramework(TestFrameworkType.Platform)
    }

    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test"))
}

tasks.test {
    maxHeapSize = "2g"
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            // 2024.2 is 242.* (so 242 is the correct baseline)
            sinceBuild = "242"
            untilBuild = "252.*"
        }
    }

    pluginVerification {
        ides {
            create(IntelliJPlatformType.IntellijIdea, "2024.2")
            create(IntelliJPlatformType.IntellijIdea, "2024.3")
            create(IntelliJPlatformType.IntellijIdea, "2025.2")
        }
    }
}

spotless {
    kotlin {
        ktlint()
        licenseHeaderFile(rootProject.file("spotless/HEADER.kt"), "package ")
    }

    kotlinGradle {
        ktlint()
    }
}
