/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.psi.core.source

/** Value-only source graph inputs. No PSI, documents or mutable editor references escape a read action. */
enum class SourceEntityKind { CLASS, INTERFACE, OBJECT, FUNCTION, PROPERTY, METHOD, FIELD, TYPE_ALIAS }

data class SourceEntityId(
    val moduleId: String,
    val sourceRoot: String,
    val fileUrl: String,
    val declarationKind: SourceEntityKind,
    val signature: String,
)

data class SourceEntity(
    val id: SourceEntityId,
    val packageName: String,
    val displayName: String,
    val offset: Int,
)

data class SourceReference(
    val fileUrl: String,
    val offset: Int,
    val length: Int,
    val documentStamp: Long,
)

enum class SourceDependencyKind { INHERITANCE, TYPE, ANNOTATION, CONSTRUCTOR, CALL, ACCESS }

sealed interface SourceTarget {
    data class Internal(val entity: SourceEntityId) : SourceTarget

    data class External(val name: String) : SourceTarget

    data class Unresolved(val name: String) : SourceTarget
}

data class SourceDependency(
    val from: SourceEntityId,
    val target: SourceTarget,
    val kind: SourceDependencyKind,
    val evidence: SourceReference,
)

data class SourceDiagnostic(
    val fileUrl: String?,
    val phase: String,
    val message: String,
)

data class SourceAnalysisProfile(
    val id: String = "ide-source-v1",
    val maxFiles: Int = 50_000,
    val maxRelationships: Int = 500_000,
    val maxWitnesses: Int = 20,
)

data class SourceCoverage(
    val eligibleFiles: Int,
    val analyzedFiles: Int,
    val failedFiles: Int,
    val resolvedOccurrences: Long,
    val unresolvedOccurrences: Long,
    val excludedFiles: Int,
    val truncated: Boolean,
)

data class SourceRelationship(
    val from: SourceEntityId,
    val target: SourceEntityId,
    val kinds: Set<SourceDependencyKind>,
    val occurrences: Long,
    val witnesses: List<SourceReference>,
)

data class ProjectSourceSnapshot(
    val profile: SourceAnalysisProfile,
    val entities: List<SourceEntity>,
    val references: List<SourceDependency>,
    val relationships: List<SourceRelationship>,
    val diagnostics: List<SourceDiagnostic>,
    val coverage: SourceCoverage,
    val complete: Boolean,
)
