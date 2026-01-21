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
package io.shamash.asm.core.export.facts

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.shamash.asm.core.config.schema.v1.model.ExportFactsFormat
import io.shamash.asm.core.facts.model.DependencyKind
import io.shamash.asm.core.facts.model.OriginKind
import io.shamash.asm.core.facts.model.Visibility
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream

/**
 * Streaming reader for exported facts.
 */
object FactsReader {
    private val mapper: ObjectMapper = jacksonObjectMapper()

    data class ReadResult(
        val meta: FactsMetaRecord?,
        val classCount: Int,
        val edgeCount: Int,
    )

    fun detectFormat(path: Path): ExportFactsFormat {
        val name = path.fileName.toString().lowercase()
        return if (name.endsWith(".jsonl.gz")) ExportFactsFormat.JSONL_GZ else ExportFactsFormat.JSON
    }

    /**
     * Read exported facts and invoke callbacks per record.
     *
     * Use this to stream huge graphs without loading everything into memory.
     */
    fun read(
        path: Path,
        onMeta: (FactsMetaRecord) -> Unit = {},
        onClass: (FactsClassRecord) -> Unit = {},
        onEdge: (FactsEdgeRecord) -> Unit = {},
    ): ReadResult {
        val format = detectFormat(path)
        return when (format) {
            ExportFactsFormat.JSONL_GZ -> readJsonlGz(path, onMeta, onClass, onEdge)
            ExportFactsFormat.JSON -> readJson(path, onMeta, onClass, onEdge)
        }
    }

    private fun readJsonlGz(
        path: Path,
        onMeta: (FactsMetaRecord) -> Unit,
        onClass: (FactsClassRecord) -> Unit,
        onEdge: (FactsEdgeRecord) -> Unit,
    ): ReadResult {
        var meta: FactsMetaRecord? = null
        var classes = 0
        var edges = 0

        Files.newInputStream(path).use { fis ->
            GZIPInputStream(fis).use { gz ->
                gz.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        val s = line.trim()
                        if (s.isEmpty()) return@forEach

                        val node = mapper.readTree(s)
                        val rt = node.str("recordType")
                        when (rt) {
                            FactsExportSchema.RECORD_META -> {
                                val rec = parseMeta(node)
                                meta = rec
                                onMeta(rec)
                            }
                            FactsExportSchema.RECORD_CLASS -> {
                                val rec = parseClass(node)
                                classes++
                                onClass(rec)
                            }
                            FactsExportSchema.RECORD_EDGE -> {
                                val rec = parseEdge(node)
                                edges++
                                onEdge(rec)
                            }
                            else -> {
                                // ignore unknown records for forward-compatibility
                            }
                        }
                    }
                }
            }
        }

        return ReadResult(meta = meta, classCount = classes, edgeCount = edges)
    }

    private data class FactsJsonDocument(
        val schemaId: String,
        val schemaVersion: Int,
        val toolName: String,
        val toolVersion: String,
        val generatedAtEpochMillis: Long,
        val projectName: String,
        val classes: List<FactsClassRecord>,
        val edges: List<FactsEdgeRecord>,
    )

    private fun readJson(
        path: Path,
        onMeta: (FactsMetaRecord) -> Unit,
        onClass: (FactsClassRecord) -> Unit,
        onEdge: (FactsEdgeRecord) -> Unit,
    ): ReadResult {
        Files.newBufferedReader(path, StandardCharsets.UTF_8).use { r ->
            val doc = mapper.readValue(r, FactsJsonDocument::class.java)
            val meta =
                FactsMetaRecord(
                    toolName = doc.toolName,
                    toolVersion = doc.toolVersion,
                    generatedAtEpochMillis = doc.generatedAtEpochMillis,
                    projectName = doc.projectName,
                )
            onMeta(meta)
            doc.classes.forEach(onClass)
            doc.edges.forEach(onEdge)
            return ReadResult(meta = meta, classCount = doc.classes.size, edgeCount = doc.edges.size)
        }
    }

    private fun parseMeta(node: JsonNode): FactsMetaRecord =
        FactsMetaRecord(
            toolName = node.str("toolName"),
            toolVersion = node.str("toolVersion"),
            generatedAtEpochMillis = node.long("generatedAtEpochMillis"),
            projectName = node.str("projectName"),
        )

    private fun parseClass(node: JsonNode): FactsClassRecord =
        FactsClassRecord(
            fqName = node.str("fqName"),
            packageName = node.str("packageName"),
            simpleName = node.str("simpleName"),
            role = node.optStr("role"),
            visibility = Visibility.valueOf(node.str("visibility")),
            isInterface = node.bool("isInterface"),
            isAbstract = node.bool("isAbstract"),
            isEnum = node.bool("isEnum"),
            hasMainMethod = node.bool("hasMainMethod"),
            methodCount = node.int("methodCount"),
            fieldCount = node.int("fieldCount"),
            originKind = OriginKind.valueOf(node.str("originKind")),
            originPath = node.str("originPath"),
            containerPath = node.optStr("containerPath"),
            entryPath = node.optStr("entryPath"),
            sourceFile = node.optStr("sourceFile"),
            line = node.optInt("line"),
        )

    private fun parseEdge(node: JsonNode): FactsEdgeRecord =
        FactsEdgeRecord(
            from = node.str("from"),
            to = node.str("to"),
            kind = DependencyKind.valueOf(node.str("kind")),
            detail = node.optStr("detail"),
            originKind = OriginKind.valueOf(node.str("originKind")),
            originPath = node.str("originPath"),
            containerPath = node.optStr("containerPath"),
            entryPath = node.optStr("entryPath"),
            sourceFile = node.optStr("sourceFile"),
            line = node.optInt("line"),
        )

    private fun JsonNode.str(name: String): String = get(name)?.asText() ?: ""

    private fun JsonNode.optStr(name: String): String? = get(name)?.takeUnless { it.isNull }?.asText()

    private fun JsonNode.bool(name: String): Boolean = get(name)?.asBoolean() ?: false

    private fun JsonNode.int(name: String): Int = get(name)?.asInt() ?: 0

    private fun JsonNode.long(name: String): Long = get(name)?.asLong() ?: 0L

    private fun JsonNode.optInt(name: String): Int? = get(name)?.takeUnless { it.isNull }?.asInt()
}
