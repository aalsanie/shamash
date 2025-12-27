/*
 *
 * shamash is an architectural refactoring tool (currently: intellij plugin) that enforces clean architecture.
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

plugins {
    kotlin("jvm") version "1.9.23"
    id("org.jetbrains.intellij") version "1.17.3"
    id("com.diffplug.spotless") version "8.1.0"
}

group = "io.shamash"
version = "0.3.0"

repositories {
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    implementation("org.ow2.asm:asm:9.7.1")
    implementation("org.ow2.asm:asm-util:9.7.1")
}

intellij {
    version.set("2023.3")
    type.set("IC")
    plugins.set(listOf("java"))
}

tasks {
    patchPluginXml {
        sinceBuild.set("233")
    }
}

tasks {
    runPluginVerifier {
        ideVersions.set(listOf("2023.3", "2024.1", "2024.2", "2024.3"))
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
