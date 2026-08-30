/*
 * Copyright © 2025-2026 | Shamash
 *
 * Shamash is a JVM architecture enforcement tool that helps teams
 * define, validate, and continuously enforce architectural boundaries.
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
package io.shamash.cli

import io.shamash.asm.core.config.schema.v1.model.BaselineMode
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

internal data class BaselineConfigEditPlan(
    val path: Path,
    val updated: String?,
) {
    fun apply() {
        val content = updated ?: return
        val tmp = path.resolveSibling("${path.fileName}.tmp")
        Files.writeString(tmp, content, StandardCharsets.UTF_8)
        try {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    companion object {
        fun prepare(
            path: Path,
            currentMode: BaselineMode,
        ): BaselineConfigEditPlan {
            if (currentMode == BaselineMode.VERIFY) return BaselineConfigEditPlan(path, null)

            val text = Files.readString(path, StandardCharsets.UTF_8)
            val lines = text.splitToSequence("\n").toMutableList()
            val baselineIndex =
                lines.indexOfFirst {
                    it.trim() == "baseline:" && it.takeWhile(Char::isWhitespace).isEmpty()
                }
            require(baselineIndex >= 0) {
                "Cannot switch baseline.mode to VERIFY automatically: top-level baseline block not found."
            }

            var modeIndex = -1
            for (i in baselineIndex + 1 until lines.size) {
                val line = lines[i]
                if (line.isNotBlank() && !line.first().isWhitespace() && !line.trimStart().startsWith("#")) break
                if (Regex("^\\s+mode\\s*:").containsMatchIn(line)) {
                    modeIndex = i
                    break
                }
            }
            require(modeIndex >= 0) {
                "Cannot switch baseline.mode to VERIFY automatically: block-style baseline.mode not found."
            }

            val line = lines[modeIndex]
            val match =
                Regex("^(\\s*)mode\\s*:\\s*[^#\\r]*?(\\s*(#.*)?)$").matchEntire(line)
                    ?: error("Cannot safely update baseline.mode in $path")
            val indent = match.groupValues[1]
            val suffix = match.groupValues[2]
            lines[modeIndex] = "${indent}mode: VERIFY$suffix"

            val newline = if (text.endsWith("\n")) "\n" else ""
            val updated = lines.joinToString("\n").trimEnd('\n') + newline
            return BaselineConfigEditPlan(path, updated)
        }
    }
}
