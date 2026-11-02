/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.asm.core.export.roles

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.shamash.asm.core.config.schema.v1.model.RoleDef
import io.shamash.asm.core.facts.query.FactIndex
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

object RolesExporter {
    private val mapper = jacksonObjectMapper()

    fun export(
        facts: FactIndex,
        roleDefs: Map<String, RoleDef>,
        outputPath: Path,
        toolName: String,
        toolVersion: String,
        projectName: String,
        generatedAtEpochMillis: Long,
    ) {
        val rolesSorted = roleDefs.entries.sortedBy { it.key }

        val roleToClasses = facts.roles

        val entries =
            rolesSorted.map { (roleId, def) ->
                val classes = roleToClasses[roleId]?.toList()?.sorted() ?: emptyList()
                RolesJsonDocument.RoleEntry(
                    id = roleId,
                    priority = def.priority,
                    description = def.description?.trim()?.takeIf { it.isNotEmpty() },
                    matcher = mapper.valueToTree(def.match),
                    count = classes.size,
                    classes = classes,
                )
            }

        val totalClasses = facts.classes.size
        val matchedClasses = facts.classToRole.size

        val doc =
            RolesJsonDocument(
                projectName = projectName,
                toolName = toolName,
                toolVersion = toolVersion,
                generatedAtEpochMillis = generatedAtEpochMillis,
                totals =
                    RolesJsonDocument.Totals(
                        roles = roleDefs.size,
                        classesTotal = totalClasses,
                        classesMatched = matchedClasses,
                        classesUnmatched = (totalClasses - matchedClasses).coerceAtLeast(0),
                    ),
                roles = entries,
            )

        outputPath.parent?.let { Files.createDirectories(it) }
        Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8).use { w ->
            mapper.writerWithDefaultPrettyPrinter().writeValue(w, doc)
        }
    }
}
