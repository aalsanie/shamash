/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.asm.core.export.facts

import io.shamash.asm.core.facts.model.DependencyKind
import io.shamash.asm.core.facts.model.OriginKind
import io.shamash.asm.core.facts.model.Visibility

/** Versioned transport records consumed by CLI/UI; prefer additive changes for compatibility. */
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
    val originKind: OriginKind,
    val originPath: String,
    val containerPath: String?,
    val entryPath: String?,
    val sourceFile: String?,
    val line: Int?,
) : FactsExportRecord
