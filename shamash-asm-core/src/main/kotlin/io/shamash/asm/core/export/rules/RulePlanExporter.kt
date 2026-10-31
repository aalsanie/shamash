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
package io.shamash.asm.core.export.rules

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.shamash.artifacts.contract.FindingSeverity
import io.shamash.asm.core.config.schema.v1.model.RuleScope
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

internal enum class RulePlanStatus {
    DISABLED,
    NOT_FOUND,
    NO_ROLES,
    EXCLUDED,
    EXECUTED,
    FAILED,
}

internal data class RulePlanEntry(
    val ruleId: String,
    val resolvedRuleId: String?,
    val role: String?,
    val status: RulePlanStatus,
    val severity: FindingSeverity,
    val scope: RuleScope?,
    val findingsProduced: Int = 0,
)

private data class RulePlanDocument(
    val schemaId: String = "shamash.rules.plan",
    val schemaVersion: Int = 1,
    val toolName: String,
    val toolVersion: String,
    val projectName: String,
    val generatedAtEpochMillis: Long,
    val rules: List<RulePlanEntry>,
)

internal object RulePlanExporter {
    private val mapper = jacksonObjectMapper()

    fun export(
        entries: List<RulePlanEntry>,
        outputPath: Path,
        toolName: String,
        toolVersion: String,
        projectName: String,
        generatedAtEpochMillis: Long,
    ) {
        val document =
            RulePlanDocument(
                toolName = toolName,
                toolVersion = toolVersion,
                projectName = projectName,
                generatedAtEpochMillis = generatedAtEpochMillis,
                rules = entries,
            )

        val directory = outputPath.toAbsolutePath().parent
        Files.createDirectories(directory)
        val temporary = Files.createTempFile(directory, "rule-plan-", ".tmp")
        try {
            Files.newBufferedWriter(temporary, StandardCharsets.UTF_8).use { writer ->
                mapper.writeValue(writer, document)
            }
            Files.move(temporary, outputPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
