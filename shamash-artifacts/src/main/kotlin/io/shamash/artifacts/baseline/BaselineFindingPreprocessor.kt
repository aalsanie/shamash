/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.artifacts.baseline

import io.shamash.artifacts.contract.Finding
import io.shamash.artifacts.util.PathNormalizer
import java.nio.file.Path
import java.nio.file.Paths

class BaselineFindingPreprocessor(
    private val baselineFingerprints: Set<String>,
) {
    fun process(
        projectBasePath: Path,
        findings: List<Finding>,
    ): List<Finding> {
        if (baselineFingerprints.isEmpty() || findings.isEmpty()) return findings

        val out = ArrayList<Finding>(findings.size)
        for (finding in findings) {
            val normalizedPath =
                PathNormalizer.relativizeOrNormalize(
                    base = projectBasePath,
                    target = Paths.get(finding.filePath),
                )

            val fingerprint = BaselineFingerprint.sha256Hex(finding, normalizedPath)
            if (!baselineFingerprints.contains(fingerprint)) {
                out.add(finding)
            }
        }
        return out
    }
}
