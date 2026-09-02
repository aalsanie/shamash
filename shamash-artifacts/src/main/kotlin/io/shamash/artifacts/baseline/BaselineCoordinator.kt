/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
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

    fun createSuppressionPreprocessor(baselineFingerprints: Set<String>): BaselineFindingPreprocessor? {
        if (baselineFingerprints.isEmpty()) return null
        return BaselineFindingPreprocessor(baselineFingerprints)
    }
}
