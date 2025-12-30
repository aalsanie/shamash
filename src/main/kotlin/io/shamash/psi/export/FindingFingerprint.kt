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

import io.shamash.psi.engine.Finding
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * fingerprinting for findings.
 *
 * This is the official hook for baseline mode.
 *
 * Rules:
 * - Must be stable across machines/OS (paths should be normalized BEFORE fingerprinting).
 * - Must not include absolute paths.
 * - Must be robust against formatting noise.
 *
 * Current fingerprint components:
 * - ruleId
 * - severity
 * - normalized file path
 * - classFqn (optional)
 * - memberName (optional)
 * - message
 *
 */
object FindingFingerprint {
    /**
     * Compute a stable SHA-256 hex fingerprint for a [Finding] using a normalized file path.
     */
    fun sha256Hex(
        finding: Finding,
        normalizedProjectRelativePath: String,
    ): String {
        val preimage = buildPreimage(finding, normalizedProjectRelativePath)
        val digest =
            MessageDigest
                .getInstance("SHA-256")
                .digest(preimage.toByteArray(StandardCharsets.UTF_8))
        return toHex(digest)
    }

    private fun buildPreimage(
        finding: Finding,
        normalizedProjectRelativePath: String,
    ): String {
        // Separator must never appear in normalized fields (we use ASCII Unit Separator).
        val sep = '\u001F'
        return buildString(256) {
            append("v1").append(sep)
            append(finding.ruleId).append(sep)
            append(finding.severity.name).append(sep)
            append(normalizedProjectRelativePath).append(sep)
            append(finding.classFqn ?: "").append(sep)
            append(finding.memberName ?: "").append(sep)
            append(finding.message)
        }
    }

    private fun toHex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        var j = 0
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            val hi = v ushr 4
            val lo = v and 0x0F
            out[j++] = HEX[hi]
            out[j++] = HEX[lo]
        }
        return String(out)
    }

    private val HEX: CharArray = "0123456789abcdef".toCharArray()
}
