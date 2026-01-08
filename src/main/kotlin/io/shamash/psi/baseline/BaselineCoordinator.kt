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
import io.shamash.psi.util.PathNormalizer
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest

/**
 * Coordinates baseline read/write and fingerprint computation for scan/export wiring.
 *
 * Baseline owns:
 * - stable fingerprinting spec (SHA-256 over normalized finding identity fields)
 * - baseline.json persistence
 *
 * Baseline does NOT:
 * - load/validate config
 * - extract facts
 * - execute rules
 * - handle exception/inline suppression
 * - depend on export/presentation layers
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
     * Fingerprinting uses:
     * - a normalized project-relative path derived from [projectBasePath] + finding.filePath
     * - finding identity fields (ruleId, severity, offsets, data) with deterministic ordering
     *
     * IMPORTANT: message text is intentionally excluded to avoid baselines breaking
     * on message wording changes.
     */
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
     * Creates a baseline suppression preprocessor.
     *
     * Returns null when [baselineFingerprints] is empty.
     */
    fun createSuppressionPreprocessor(baselineFingerprints: Set<String>): BaselineFindingPreprocessor? {
        if (baselineFingerprints.isEmpty()) return null
        return BaselineFindingPreprocessor(baselineFingerprints)
    }
}

/**
 * Baseline fingerprinting spec (owned by baseline layer).
 * Used by export
 * This is deterministic and stable across OSes by:
 * - using project-relative normalized paths (forward slashes, no drive letters)
 * - sorting data keys/values
 * - excluding human-readable message text
 */
object BaselineFingerprint {
    fun sha256Hex(
        finding: Finding,
        normalizedProjectRelativePath: String,
    ): String {
        val md = MessageDigest.getInstance("SHA-256")

        fun put(s: String) {
            md.update(s.toByteArray(StandardCharsets.UTF_8))
            md.update(0x1F) // unit separator
        }

        put("v1") // baseline fingerprint version
        put(normalizedProjectRelativePath)

        put(finding.ruleId)
        put(finding.severity.name)

        put(finding.startOffset?.toString() ?: "")
        put(finding.endOffset?.toString() ?: "")

        // Deterministic data encoding: sort by key then by value
        val entries = finding.data.entries.sortedWith(compareBy<Map.Entry<String, String>> { it.key }.thenBy { it.value })
        for ((k, v) in entries) {
            put(k)
            put(v)
        }

        return md.digest().toHexLower()
    }

    private fun ByteArray.toHexLower(): String {
        val out = CharArray(this.size * 2)
        var j = 0
        for (b in this) {
            val i = b.toInt() and 0xFF
            val hi = i ushr 4
            val lo = i and 0x0F
            out[j++] = HEX[hi]
            out[j++] = HEX[lo]
        }
        return String(out)
    }

    private val HEX: CharArray = "0123456789abcdef".toCharArray()
}
