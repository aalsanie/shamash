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
package io.shamash.artifacts.util

import java.nio.file.Path
import java.nio.file.Paths

/**
 * Normalizes paths for cross-platform reporting/baseline.
 */
object PathNormalizer {
    fun normalize(path: String): String = normalize(Paths.get(path))

    fun normalize(path: Path): String {
        val raw = path.toString().replace('\\', '/')

        // Strip Windows drive letters to avoid fingerprint mismatches
        // Example: "C:/x/y" -> "/x/y"
        val driveStripped = raw.replace(Regex("^[A-Za-z]:/"), "/")

        // Collapse repeated slashes
        return driveStripped.replace(Regex("/+"), "/")
    }

    /**
     * Produce a stable relative path from [base] to [target] when possible.
     * Falls back to normalized absolute target when relativization fails.
     */
    fun relativizeOrNormalize(
        base: Path,
        target: Path,
    ): String =
        try {
            val rel = base.toAbsolutePath().normalize().relativize(target.toAbsolutePath().normalize())
            normalize(rel)
        } catch (_: Exception) {
            normalize(target)
        }
}
