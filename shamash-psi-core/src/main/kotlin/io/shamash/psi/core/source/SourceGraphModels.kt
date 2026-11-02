/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.psi.core.source

/** Exact IDE module and exact declared package, not a package prefix or an inferred layer. */
data class SourcePackageId(
    val moduleId: String,
    val packageName: String,
) : Comparable<SourcePackageId> {
    override fun compareTo(other: SourcePackageId): Int =
        compareValuesBy(this, other, SourcePackageId::moduleId, SourcePackageId::packageName)
}

/** One verified source occurrence for a directed package edge. */
data class SourcePackageWitness(
    val from: SourceEntityId,
    val target: SourceEntityId,
    val kind: SourceDependencyKind,
    val reference: SourceReference,
)

/** Counts and evidence are independent: twenty calls to one target remain one distinct relationship. */
data class SourcePackageDependency(
    val from: SourcePackageId,
    val target: SourcePackageId,
    val distinctEntityRelationships: Int,
    val occurrences: Long,
    val kinds: Set<SourceDependencyKind>,
    val witnesses: List<SourcePackageWitness>,
)

/** Advisory only. The witness is a real closed walk; membership can be larger than the walk. */
data class SourcePackageCycle(
    val id: String,
    val members: List<SourcePackageId>,
    val witness: List<SourcePackageDependency>,
) {
    val title: String
        get() = "Cyclic group of ${members.size} packages"
}

data class SourceDependencySummary(
    val distinctTargets: Int,
    val occurrences: Long,
)

/** External references are bounded samples in S01, not proven complete dependency totals. */
data class SourceExternalReference(
    val from: SourceEntityId,
    val name: String,
    val kind: SourceDependencyKind,
    val evidence: SourceReference,
)

data class SourceGraphSnapshot(
    val profileId: String,
    val coverage: SourceCoverage,
    val complete: Boolean,
    val diagnostics: List<SourceDiagnostic>,
    val entities: List<SourceEntity>,
    val entityUses: Map<SourceEntityId, List<SourceEntityId>>,
    val entityUsedBy: Map<SourceEntityId, List<SourceEntityId>>,
    val entityCounts: Map<SourceEntityId, SourceDependencySummary>,
    val entityIncomingCounts: Map<SourceEntityId, SourceDependencySummary>,
    val packages: Map<SourcePackageId, List<SourceEntityId>>,
    val packageDependencies: List<SourcePackageDependency>,
    val packageUses: Map<SourcePackageId, List<SourcePackageId>>,
    val packageUsedBy: Map<SourcePackageId, List<SourcePackageId>>,
    val packageCounts: Map<SourcePackageId, SourceDependencySummary>,
    val packageIncomingCounts: Map<SourcePackageId, SourceDependencySummary>,
    val externalEvidence: List<SourceExternalReference>,
    val externalEvidenceIsSampled: Boolean,
    val cycles: List<SourcePackageCycle>,
) {
    /** A partial graph with no observed cycles must not be described as cycle-free. */
    val cycleAbsenceEstablished: Boolean get() = complete && cycles.isEmpty()
}
