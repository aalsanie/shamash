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

class BaselineCoordinator(
    private val store: BaselineStore = BaselineStore(),
) {
    fun loadBaselineFingerprints(outputDir: Path): Set<String> {
        val baseline = store.load(outputDir) ?: return emptySet()
        return baseline.fingerprints
    }

    /** Uses project-relative paths and the identity contract in [BaselineFingerprint]. */
    fun computeFingerprints(
        projectBasePath: Path,
        findings: List<Finding>,
    ): Set<String> {
        if (findings.isEmpty()) return emptySet()

        val out = LinkedHashSet<String>(findings.size)
        for (finding in findings) {
            val normalizedPath =
                PathNormalizer.relativizeOrNormalize(
                    base = projectBasePath,
                    target = Paths.get(finding.filePath),
                )

            out.add(BaselineFingerprint.sha256Hex(finding, normalizedPath))
        }
        return out
    }

    fun prepareBaseline(
        outputDir: Path,
        fingerprints: Set<String>,
        mergeWithExisting: Boolean,
    ): Set<String> {
        val existing = if (mergeWithExisting) store.load(outputDir)?.fingerprints.orEmpty() else emptySet()
        val prepared = LinkedHashSet<String>(existing.size + fingerprints.size)
        prepared.addAll(existing)
        prepared.addAll(fingerprints)
        store.serialize(prepared)
        return prepared
    }

    fun writeBaseline(
        outputDir: Path,
        fingerprints: Set<String>,
        mergeWithExisting: Boolean,
    ) {
        store.write(outputDir, prepareBaseline(outputDir, fingerprints, mergeWithExisting))
    }

    fun createSuppressionPreprocessor(baselineFingerprints: Set<String>): BaselineFindingPreprocessor? {
        if (baselineFingerprints.isEmpty()) return null
        return BaselineFindingPreprocessor(baselineFingerprints)
    }
}
