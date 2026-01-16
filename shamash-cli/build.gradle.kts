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

plugins {
    application
}

dependencies {
    implementation(project(":shamash-asm-core"))
    implementation(project(":shamash-export"))
    implementation(project(":shamash-artifacts"))

    // lightweight, Kotlin-native CLI parser
    implementation("org.jetbrains.kotlinx:kotlinx-cli:0.3.6")
}

application {
    mainClass.set("io.shamash.cli.MainKt")
}

tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to "shamash-cli",
            "Implementation-Version" to project.version.toString(),
        )
    }
}
