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
package io.shamash.psi.baseline

import io.shamash.psi.engine.Finding
import io.shamash.psi.export.FindingFingerprint
import io.shamash.psi.export.FindingPreprocessor
import io.shamash.psi.export.PathNormalizer
import java.nio.file.Path

/**
 * Suppresses findings that match an existing baseline by stable fingerprint.
 *
 * Fingerprints are computed using the project's base path and a project-relative
 * normalized file path (forward slashes), consistent with exporter fingerprinting.
 */
class BaselineFindingPreprocessor(
    private val baselineFingerprints: Set<String>,
) : FindingPreprocessor {
    override fun process(
        projectBasePath: Path,
        findings: List<Finding>,
    ): List<Finding> {
        if (baselineFingerprints.isEmpty() || findings.isEmpty()) return findings

        val out = ArrayList<Finding>(findings.size)
        for (finding in findings) {
            val normalizedPath =
                PathNormalizer.toProjectRelativeNormalizedPath(
                    basePath = projectBasePath,
                    filePath = finding.filePath,
                )

            val fingerprint = FindingFingerprint.sha256Hex(finding, normalizedPath)
            if (!baselineFingerprints.contains(fingerprint)) {
                out.add(finding)
            }
        }
        return out
    }
}
