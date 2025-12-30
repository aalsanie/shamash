/*
 * Copyright © 2025-2026 | Shamash is a refactoring tool that enforces clean architecture.
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
package io.shamash.psi.export

import java.nio.file.Path
import java.nio.file.Paths

/**
 * Canonical path normalization utilities used by exporters.
 *
 * Rules:
 * - Base path is normalized to an absolute path with forward slashes.
 * - Exported file paths are project-root-relative with forward slashes.
 * - If a path can't be relativized, we fall back to a best-effort normalized path.
 */
object PathNormalizer {
    fun normalizeBasePath(basePath: Path): String = normalizeToForwardSlashes(basePath.toAbsolutePath().normalize().toString())

    fun toProjectRelativeNormalizedPath(
        basePath: Path,
        filePath: String?,
    ): String {
        val raw = filePath?.trim().orEmpty()
        if (raw.isBlank()) return ""

        val base = basePath.toAbsolutePath().normalize()
        val candidate = safeToPath(raw) ?: return normalizeToForwardSlashes(raw)

        val normalizedCandidate = candidate.toAbsolutePath().normalize()

        val relative =
            try {
                base.relativize(normalizedCandidate).toString()
            } catch (_: Throwable) {
                // not under base or different root (Windows drive), fall back.
                normalizedCandidate.toString()
            }

        return normalizeToForwardSlashes(relative)
    }

    private fun safeToPath(raw: String): Path? =
        try {
            Paths.get(raw)
        } catch (_: Throwable) {
            null
        }

    private fun normalizeToForwardSlashes(path: String): String {
        // trim any leading "./" fragments that can appear after relativize
        val normalized = path.replace('\\', '/')
        return if (normalized.startsWith("./")) normalized.substring(2) else normalized
    }
}
