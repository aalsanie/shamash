/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.artifacts.util

import java.nio.file.Path
import java.nio.file.Paths

object PathNormalizer {
    fun normalize(path: String): String = normalize(Paths.get(path))

    fun normalize(path: Path): String {
        val raw = path.toString().replace('\\', '/')

        // Strip Windows drive letters to avoid fingerprint mismatches
        val driveStripped = raw.replace(Regex("^[A-Za-z]:/"), "/")

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
            val basePath = base.toAbsolutePath().normalize()
            val targetPath = basePath.resolve(target).normalize()
            normalize(basePath.relativize(targetPath))
        } catch (_: Exception) {
            normalize(target)
        }
}
