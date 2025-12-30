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
import io.shamash.psi.export.PathNormalizer
import java.nio.file.Path

/**
 * Coordinates baseline read/write and fingerprint computation for scan/export wiring.
 *
 * This class is intentionally independent of the scan runner and export pipeline factory.
 * The runner/factory can call into this class to:
 * - load baseline fingerprints (for suppression),
 * - compute fingerprints from findings,
 * - write a new/merged baseline.
 */
class BaselineCoordinator(
    private val store: BaselineStore = BaselineStore(),
) {
    /**
     * Loads baseline fingerprints from `<outputDir>/baseline.json`.
     *
     * Returns an empty set when no baseline exists.
     */
    fun loadBaselineFingerprints(outputDir: Path): Set<String> {
        val baseline = store.load(outputDir) ?: return emptySet()
        return baseline.fingerprints
    }

    /**
     * Computes stable fingerprints for the provided findings.
     *
     * Fingerprinting is based on:
     * - normalized project-relative path derived from [projectBasePath] and [Finding.filePath],
     * - finding content via [FindingFingerprint].
     */
    fun computeFingerprints(
        projectBasePath: Path,
        findings: List<Finding>,
    ): Set<String> {
        if (findings.isEmpty()) return emptySet()

        val out = LinkedHashSet<String>(findings.size)
        for (finding in findings) {
            val normalizedPath =
                PathNormalizer.toProjectRelativeNormalizedPath(
                    basePath = projectBasePath,
                    filePath = finding.filePath,
                )
            out.add(FindingFingerprint.sha256Hex(finding, normalizedPath))
        }
        return out
    }

    /**
     * Writes baseline to `<outputDir>/baseline.json`.
     *
     * If [mergeWithExisting] is true and an existing baseline exists, the union is written.
     */
    fun writeBaseline(
        outputDir: Path,
        fingerprints: Set<String>,
        mergeWithExisting: Boolean,
    ) {
        if (!mergeWithExisting) {
            store.write(outputDir, fingerprints)
            return
        }

        val existing = store.load(outputDir)?.fingerprints ?: emptySet()
        if (existing.isEmpty()) {
            store.write(outputDir, fingerprints)
            return
        }

        val merged = LinkedHashSet<String>(existing.size + fingerprints.size)
        merged.addAll(existing)
        merged.addAll(fingerprints)
        store.write(outputDir, merged)
    }

    /**
     * Creates a baseline suppression preprocessor for use in the export pipeline.
     *
     * Returns null when [baselineFingerprints] is empty.
     */
    fun createSuppressionPreprocessor(baselineFingerprints: Set<String>): BaselineFindingPreprocessor? {
        if (baselineFingerprints.isEmpty()) return null
        return BaselineFindingPreprocessor(baselineFingerprints)
    }
}
