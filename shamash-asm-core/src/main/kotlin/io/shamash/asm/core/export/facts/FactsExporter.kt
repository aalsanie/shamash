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

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.shamash.asm.core.config.schema.v1.model.ExportFactsFormat
import io.shamash.asm.core.facts.query.FactIndex
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPOutputStream
import kotlin.io.path.createDirectories

/**
 * Facts exporter.
 *
 * Default output format is JSONL_GZ:
 * - First record: meta
 * - Then N class records
 * - Then M edge records
 */
object FactsExporter {
    private val mapper: ObjectMapper =
        jacksonObjectMapper()
            .disable(SerializationFeature.INDENT_OUTPUT)

    fun export(
        facts: FactIndex,
        outputPath: Path,
        format: ExportFactsFormat,
        toolName: String,
        toolVersion: String,
        projectName: String,
        generatedAtEpochMillis: Long,
    ) {
        outputPath.parent?.createDirectories()
        when (format) {
            ExportFactsFormat.JSONL_GZ ->
                exportJsonlGz(
                    facts = facts,
                    outputPath = outputPath,
                    toolName = toolName,
                    toolVersion = toolVersion,
                    projectName = projectName,
                    generatedAtEpochMillis = generatedAtEpochMillis,
                )
            ExportFactsFormat.JSON ->
                exportJson(
                    facts = facts,
                    outputPath = outputPath,
                    toolName = toolName,
                    toolVersion = toolVersion,
                    projectName = projectName,
                    generatedAtEpochMillis = generatedAtEpochMillis,
                )
        }
    }

    private fun exportJsonlGz(
        facts: FactIndex,
        outputPath: Path,
        toolName: String,
        toolVersion: String,
        projectName: String,
        generatedAtEpochMillis: Long,
    ) {
        val methodCounts = facts.methods.groupingBy { it.owner.fqName }.eachCount()
        val fieldCounts = facts.fields.groupingBy { it.owner.fqName }.eachCount()

        val classes = facts.classes.toMutableList()
        classes.sortBy { it.fqName }

        val edges = facts.edges.toMutableList()
        edges.sortWith(
            compareBy(
                { it.from.fqName },
                { it.to.fqName },
                { it.kind.name },
                { it.detail.orEmpty() },
                { it.location.originKind.name },
                { it.location.originPath },
                { it.location.containerPath.orEmpty() },
                { it.location.entryPath.orEmpty() },
                { it.location.sourceFile.orEmpty() },
                { it.location.line ?: -1 },
            ),
        )

        Files.newOutputStream(outputPath).use { fos ->
            GZIPOutputStream(fos).use { gz ->
                gz.bufferedWriter(StandardCharsets.UTF_8).use { out ->
                    // meta first
                    out.write(
                        mapper.writeValueAsString(
                            FactsMetaRecord(
                                toolName = toolName,
                                toolVersion = toolVersion,
                                generatedAtEpochMillis = generatedAtEpochMillis,
                                projectName = projectName,
                            ),
                        ),
                    )
                    out.write("\n")

                    for (c in classes) {
                        val role = facts.classToRole[c.fqName]
                        val loc = c.location
                        val rec =
                            FactsClassRecord(
                                fqName = c.fqName,
                                packageName = c.packageName,
                                simpleName = c.simpleName,
                                role = role,
                                visibility = c.visibility,
                                isInterface = c.isInterface,
                                isAbstract = c.isAbstract,
                                isEnum = c.isEnum,
                                hasMainMethod = c.hasMainMethod,
                                methodCount = methodCounts[c.fqName] ?: 0,
                                fieldCount = fieldCounts[c.fqName] ?: 0,
                                originKind = loc.originKind,
                                originPath = loc.originPath,
                                containerPath = loc.containerPath,
                                entryPath = loc.entryPath,
                                sourceFile = loc.sourceFile,
                                line = loc.line,
                            )

                        out.write(mapper.writeValueAsString(rec))
                        out.write("\n")
                    }

                    for (e in edges) {
                        val loc = e.location
                        val rec =
                            FactsEdgeRecord(
                                from = e.from.fqName,
                                to = e.to.fqName,
                                kind = e.kind,
                                detail = e.detail,
                                originKind = loc.originKind,
                                originPath = loc.originPath,
                                containerPath = loc.containerPath,
                                entryPath = loc.entryPath,
                                sourceFile = loc.sourceFile,
                                line = loc.line,
                            )
                        out.write(mapper.writeValueAsString(rec))
                        out.write("\n")
                    }
                }
            }
        }
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

    private fun exportJson(
        facts: FactIndex,
        outputPath: Path,
        toolName: String,
        toolVersion: String,
        projectName: String,
        generatedAtEpochMillis: Long,
    ) {
        val methodCounts = facts.methods.groupingBy { it.owner.fqName }.eachCount()
        val fieldCounts = facts.fields.groupingBy { it.owner.fqName }.eachCount()

        val classes = facts.classes.toMutableList()
        classes.sortBy { it.fqName }
        val classRecords =
            classes.map { c ->
                val role = facts.classToRole[c.fqName]
                val loc = c.location
                FactsClassRecord(
                    fqName = c.fqName,
                    packageName = c.packageName,
                    simpleName = c.simpleName,
                    role = role,
                    visibility = c.visibility,
                    isInterface = c.isInterface,
                    isAbstract = c.isAbstract,
                    isEnum = c.isEnum,
                    hasMainMethod = c.hasMainMethod,
                    methodCount = methodCounts[c.fqName] ?: 0,
                    fieldCount = fieldCounts[c.fqName] ?: 0,
                    originKind = loc.originKind,
                    originPath = loc.originPath,
                    containerPath = loc.containerPath,
                    entryPath = loc.entryPath,
                    sourceFile = loc.sourceFile,
                    line = loc.line,
                )
            }

        val edges = facts.edges.toMutableList()
        edges.sortWith(
            compareBy(
                { it.from.fqName },
                { it.to.fqName },
                { it.kind.name },
                { it.detail.orEmpty() },
            ),
        )
        val edgeRecords =
            edges.map { e ->
                val loc = e.location
                FactsEdgeRecord(
                    from = e.from.fqName,
                    to = e.to.fqName,
                    kind = e.kind,
                    detail = e.detail,
                    originKind = loc.originKind,
                    originPath = loc.originPath,
                    containerPath = loc.containerPath,
                    entryPath = loc.entryPath,
                    sourceFile = loc.sourceFile,
                    line = loc.line,
                )
            }

        val doc =
            FactsJsonDocument(
                schemaId = FactsExportSchema.SCHEMA_ID,
                schemaVersion = FactsExportSchema.SCHEMA_VERSION,
                toolName = toolName,
                toolVersion = toolVersion,
                generatedAtEpochMillis = generatedAtEpochMillis,
                projectName = projectName,
                classes = classRecords,
                edges = edgeRecords,
            )

        Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8).use { w ->
            mapper.writeValue(w, doc)
        }
    }
}
