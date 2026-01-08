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

import io.shamash.psi.util.json.JsonEscaper
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.name

/**
 * Stores and loads Shamash baseline data used to suppress pre-existing findings.
 *
 * Baseline file location:
 * - `<projectRoot>/shamash/baseline.json` (i.e., under the export output directory)
 *
 * Format (versioned, minimal, dependency-free JSON):
 * {
 *   "version": 1,
 *   "fingerprints": ["<fp1>", "<fp2>", ...]
 * }
 */
class BaselineStore {
    data class Baseline(
        val version: Int,
        val fingerprints: Set<String>,
    )

    private companion object {
        private const val FILE_NAME: String = "baseline.json"
        private const val VERSION: Int = 1
    }

    fun baselinePath(outputDir: Path): Path = outputDir.resolve(FILE_NAME).normalize()

    /**
     * Loads baseline from `<outputDir>/baseline.json`.
     *
     * Returns null if the file does not exist.
     *
     * @throws IllegalStateException if the file exists but cannot be parsed safely.
     */
    fun load(outputDir: Path): Baseline? {
        val path = baselinePath(outputDir)
        if (!Files.exists(path)) return null

        val raw = Files.readString(path, StandardCharsets.UTF_8)
        val parsed = parseBaseline(raw)

        if (parsed.version != VERSION) {
            throw IllegalStateException(
                "Unsupported baseline version ${parsed.version} in $path (supported: $VERSION).",
            )
        }
        return parsed
    }

    /**
     * Writes baseline to `<outputDir>/baseline.json` atomically (best-effort on the current filesystem).
     */
    fun write(
        outputDir: Path,
        fingerprints: Set<String>,
    ) {
        val path = baselinePath(outputDir)
        Files.createDirectories(path.parent)

        val json = serializeBaseline(fingerprints)

        val tmp = path.resolveSibling("${path.name}.tmp")
        Files.writeString(tmp, json, StandardCharsets.UTF_8)

        // Best-effort atomic move (falls back when not supported)
        try {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun serializeBaseline(fingerprints: Set<String>): String {
        val sb = StringBuilder(64 + fingerprints.size * 72)
        sb.append("{\n")
        sb.append("  \"version\": ").append(VERSION).append(",\n")
        sb.append("  \"fingerprints\": [")

        var first = true
        for (fp in fingerprints.sorted()) {
            if (!first) sb.append(',')
            sb
                .append('\n')
                .append("    \"")
                .append(JsonEscaper.escape(fp))
                .append('"')
            first = false
        }

        if (fingerprints.isNotEmpty()) {
            sb.append('\n').append("  ")
        }
        sb.append("]\n")
        sb.append("}\n")
        return sb.toString()
    }

    private fun parseBaseline(raw: String): Baseline {
        val version = parseIntField(raw, "version")
        val fingerprints = parseStringArrayField(raw, "fingerprints")
        return Baseline(
            version = version,
            fingerprints = fingerprints,
        )
    }

    private fun parseIntField(
        json: String,
        fieldName: String,
    ): Int {
        val idx = json.indexOf("\"$fieldName\"")
        if (idx < 0) throw IllegalStateException("Missing field \"$fieldName\" in baseline JSON.")

        val colon = json.indexOf(':', idx)
        if (colon < 0) throw IllegalStateException("Invalid baseline JSON: missing ':' after \"$fieldName\".")

        var i = colon + 1
        while (i < json.length && json[i].isWhitespace()) i++

        val start = i
        while (i < json.length && (json[i].isDigit() || json[i] == '-')) i++

        if (start == i) throw IllegalStateException("Invalid baseline JSON: \"$fieldName\" is not a number.")
        return json.substring(start, i).trim().toInt()
    }

    private fun parseStringArrayField(
        json: String,
        fieldName: String,
    ): Set<String> {
        val idx = json.indexOf("\"$fieldName\"")
        if (idx < 0) throw IllegalStateException("Missing field \"$fieldName\" in baseline JSON.")

        val colon = json.indexOf(':', idx)
        if (colon < 0) throw IllegalStateException("Invalid baseline JSON: missing ':' after \"$fieldName\".")

        val openBracket = json.indexOf('[', colon)
        if (openBracket < 0) throw IllegalStateException("Invalid baseline JSON: missing '[' for \"$fieldName\".")

        val closeBracket = json.indexOf(']', openBracket)
        if (closeBracket < 0) throw IllegalStateException("Invalid baseline JSON: missing ']' for \"$fieldName\".")

        val arraySlice = json.substring(openBracket + 1, closeBracket)
        return extractJsonStringLiterals(arraySlice)
    }

    private fun extractJsonStringLiterals(slice: String): Set<String> {
        val out = LinkedHashSet<String>()
        var i = 0

        while (i < slice.length) {
            while (i < slice.length && slice[i].isWhitespace()) i++
            if (i >= slice.length) break

            if (slice[i] != '"') {
                i++
                continue
            }

            i++ // skip opening quote
            val sb = StringBuilder()

            while (i < slice.length) {
                val ch = slice[i]
                when (ch) {
                    '"' -> {
                        i++ // consume closing quote
                        break
                    }
                    '\\' -> {
                        if (i + 1 >= slice.length) {
                            throw IllegalStateException("Invalid baseline JSON: trailing escape in string literal.")
                        }
                        val next = slice[i + 1]
                        when (next) {
                            '"', '\\', '/' -> sb.append(next)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 5 >= slice.length) {
                                    throw IllegalStateException("Invalid baseline JSON: incomplete unicode escape.")
                                }
                                val hex = slice.substring(i + 2, i + 6)
                                val code = hex.toInt(16)
                                sb.append(code.toChar())
                                i += 4
                            }
                            else -> throw IllegalStateException(
                                "Invalid baseline JSON: unsupported escape sequence \\$next.",
                            )
                        }
                        i += 2
                        continue
                    }
                    else -> {
                        sb.append(ch)
                        i++
                    }
                }
            }

            out.add(sb.toString())
        }

        return out
    }
}
