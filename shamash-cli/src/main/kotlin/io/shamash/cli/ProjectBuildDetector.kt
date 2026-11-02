/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.cli

import java.nio.file.Files
import java.nio.file.Path

data class BuildHint(
    val tool: String,
    val command: String,
)

object ProjectBuildDetector {
    fun detect(projectRoot: Path): BuildHint? {
        val root = projectRoot.toAbsolutePath().normalize()
        val windows = System.getProperty("os.name").lowercase().contains("win")

        val gradleWrapper = root.resolve(if (windows) "gradlew.bat" else "gradlew")
        if (Files.isRegularFile(gradleWrapper)) {
            return BuildHint("Gradle", if (windows) ".\\gradlew.bat classes" else "./gradlew classes")
        }
        if (
            Files.isRegularFile(root.resolve("build.gradle")) ||
            Files.isRegularFile(root.resolve("build.gradle.kts")) ||
            Files.isRegularFile(root.resolve("settings.gradle")) ||
            Files.isRegularFile(root.resolve("settings.gradle.kts"))
        ) {
            return BuildHint("Gradle", "gradle classes")
        }

        val mavenWrapper = root.resolve(if (windows) "mvnw.cmd" else "mvnw")
        if (Files.isRegularFile(mavenWrapper)) {
            return BuildHint("Maven", if (windows) ".\\mvnw.cmd package" else "./mvnw package")
        }
        if (Files.isRegularFile(root.resolve("pom.xml"))) {
            return BuildHint("Maven", "mvn package")
        }

        return null
    }
}
