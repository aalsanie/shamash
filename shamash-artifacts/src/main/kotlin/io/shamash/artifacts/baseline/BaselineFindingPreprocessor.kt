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
