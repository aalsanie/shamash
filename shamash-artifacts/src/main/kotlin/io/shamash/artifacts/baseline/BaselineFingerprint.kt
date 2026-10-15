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
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.collections.component1
import kotlin.collections.component2

/**
 * Fingerprints use stable finding identity fields.
 * Human-readable messages are excluded so wording changes do not invalidate baselines.
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

        val isGraphCycle = finding.ruleId == "graph.noCycles"

        put(if (isGraphCycle) "v2" else "v1") // baseline fingerprint version
        put(if (isGraphCycle) "" else normalizedProjectRelativePath)

        put(finding.ruleId)
        put(finding.severity.name)

        put(finding.startOffset?.toString() ?: "")
        put(finding.endOffset?.toString() ?: "")

        val identityData =
            if (isGraphCycle) {
                finding.data.filterKeys { it in GRAPH_CYCLE_IDENTITY_KEYS }
            } else {
                finding.data
            }
        val entries = identityData.entries.sortedWith(compareBy<Map.Entry<String, String>> { it.key }.thenBy { it.value })
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

    private val GRAPH_CYCLE_IDENTITY_KEYS = setOf("granularity", "includeExternal", "cycle")

    private val HEX: CharArray = "0123456789abcdef".toCharArray()
}
