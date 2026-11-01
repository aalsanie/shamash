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

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.core.StreamReadFeature
import io.shamash.artifacts.util.json.JsonEscaper
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.name

/** Persists versioned fingerprints in `<outputDir>/baseline.json`. */
class BaselineStore {
    data class Baseline(
        val version: Int,
        val fingerprints: Set<String>,
    )

    private companion object {
        private const val FILE_NAME: String = "baseline.json"
        private const val VERSION: Int = 1
        private val FINGERPRINT = Regex("[0-9a-f]{64}")
        private val JSON_FACTORY = JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()
    }

    fun baselinePath(outputDir: Path): Path = outputDir.resolve(FILE_NAME).normalize()

    /**
     * Loads baseline from `<outputDir>/baseline.json`.
     *
     * Returns null if the file does not exist.
     *
     * @throws IllegalStateException if the file exists but cannot be parsed safely.
     */
    fun load(outputDir: Path): Baseline? = loadFile(baselinePath(outputDir))

    /** Reads a baseline from its exact path; missing files produce null. */
    fun loadFile(path: Path): Baseline? {
        if (!Files.exists(path)) return null

        val parsed = parseBaseline(Files.readString(path, StandardCharsets.UTF_8))
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

        val json = serialize(fingerprints)

        val tmp = path.resolveSibling("${path.name}.tmp")
        Files.writeString(tmp, json, StandardCharsets.UTF_8)

        try {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** Encodes the same versioned baseline format used by the ASM engine. */
    fun serialize(fingerprints: Set<String>): String {
        require(fingerprints.all(FINGERPRINT::matches)) { "Baseline fingerprints must be lowercase SHA-256 hex strings." }
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

    private fun parseBaseline(raw: String): Baseline =
        try {
            JSON_FACTORY.createParser(raw).use { parser ->
                if (parser.nextToken() != JsonToken.START_OBJECT) {
                    invalid("expected a JSON object")
                }

                var version: Int? = null
                var fingerprints: Set<String>? = null

                while (true) {
                    val field = parser.nextToken() ?: invalid("unexpected end of object")
                    if (field == JsonToken.END_OBJECT) break
                    if (field != JsonToken.FIELD_NAME) invalid("expected a field name")

                    when (parser.currentName) {
                        "version" -> {
                            if (parser.nextToken() != JsonToken.VALUE_NUMBER_INT) {
                                invalid("\"version\" is not a number")
                            }
                            version = parser.text.toIntOrNull() ?: invalid("\"version\" must be a 32-bit integer")
                        }

                        "fingerprints" -> {
                            if (parser.nextToken() != JsonToken.START_ARRAY) {
                                invalid("\"fingerprints\" must be an array")
                            }
                            val values = LinkedHashSet<String>()
                            while (true) {
                                val token = parser.nextToken() ?: invalid("unterminated fingerprints array")
                                if (token == JsonToken.END_ARRAY) break
                                if (token != JsonToken.VALUE_STRING) invalid("fingerprints must contain only strings")
                                val fingerprint = parser.text
                                if (!FINGERPRINT.matches(fingerprint)) {
                                    invalid("fingerprint must be a lowercase 64-character SHA-256 hex string")
                                }
                                if (!values.add(fingerprint)) invalid("duplicate fingerprint")
                            }
                            fingerprints = values
                        }

                        else -> {
                            invalid("unknown field ${parser.currentName}")
                        }
                    }
                }

                if (parser.nextToken() != null) invalid("trailing data after baseline object")
                Baseline(
                    version = version ?: invalid("missing field \"version\""),
                    fingerprints = fingerprints ?: invalid("missing field \"fingerprints\""),
                )
            }
        } catch (e: JacksonException) {
            throw IllegalStateException("Invalid baseline JSON: ${e.originalMessage}", e)
        }

    private fun invalid(message: String): Nothing = throw IllegalStateException("Invalid baseline JSON: $message.")
}
