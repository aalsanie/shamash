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

import io.shamash.asm.core.facts.model.DependencyKind
import io.shamash.asm.core.facts.model.OriginKind
import io.shamash.asm.core.facts.model.Visibility

/**
 * Stable, versioned export models.
 *
 * Notes:
 * - These are *transport* records, not the internal facts graph.
 * - Prefer additive changes to maintain backward compatibility.
 */
sealed interface FactsExportRecord {
    val recordType: String
}

data class FactsMetaRecord(
    override val recordType: String = FactsExportSchema.RECORD_META,
    val schemaId: String = FactsExportSchema.SCHEMA_ID,
    val schemaVersion: Int = FactsExportSchema.SCHEMA_VERSION,
    val toolName: String,
    val toolVersion: String,
    val generatedAtEpochMillis: Long,
    val projectName: String,
) : FactsExportRecord

data class FactsClassRecord(
    override val recordType: String = FactsExportSchema.RECORD_CLASS,
    val fqName: String,
    val packageName: String,
    val simpleName: String,
    val role: String?,
    val visibility: Visibility,
    val isInterface: Boolean,
    val isAbstract: Boolean,
    val isEnum: Boolean,
    val hasMainMethod: Boolean,
    val methodCount: Int,
    val fieldCount: Int,
    // location (stable origin attribution)
    val originKind: OriginKind,
    val originPath: String,
    val containerPath: String?,
    val entryPath: String?,
    val sourceFile: String?,
    val line: Int?,
) : FactsExportRecord

data class FactsEdgeRecord(
    override val recordType: String = FactsExportSchema.RECORD_EDGE,
    val from: String,
    val to: String,
    val kind: DependencyKind,
    val detail: String?,
    // location (stable origin attribution)
    val originKind: OriginKind,
    val originPath: String,
    val containerPath: String?,
    val entryPath: String?,
    val sourceFile: String?,
    val line: Int?,
) : FactsExportRecord
