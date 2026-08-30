/*
 * Copyright © 2025-2026 | Shamash
 *
 * Shamash is a JVM architecture enforcement tool that helps teams
 * define, validate, and continuously enforce architectural boundaries.
 *
 * Author: @aalsanie
 *
 * Plugin: https://plugins.jetbrains.com/plugin/29504-shamash
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
package io.shamash.cli

import java.nio.file.Files
import java.nio.file.Path

data class BuildHint(
    val tool: String,
    val command: String,
)

/** Detects a likely build tool only to provide an actionable "compile first" message. */
object ProjectBuildDetector {
    fun detect(projectRoot: Path): BuildHint? {
        val root = projectRoot.toAbsolutePath().normalize()
        val windows = System.getProperty("os.name").lowercase().contains("win")

        if (Files.isRegularFile(root.resolve(if (windows) "gradlew.bat" else "gradlew")) ||
            Files.isRegularFile(root.resolve("build.gradle")) ||
            Files.isRegularFile(root.resolve("build.gradle.kts"))
        ) {
            return BuildHint("Gradle", if (windows) ".\\gradlew.bat classes" else "./gradlew classes")
        }

        if (Files.isRegularFile(root.resolve(if (windows) "mvnw.cmd" else "mvnw")) ||
            Files.isRegularFile(root.resolve("pom.xml"))
        ) {
            return BuildHint("Maven", if (windows) ".\\mvnw.cmd package" else "./mvnw package")
        }

        return null
    }
}
