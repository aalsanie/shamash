/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.psi.core.source

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/**
 * Pure, deterministic reduction of resolved source relationships. Never reads PSI or infers
 * imports, reverse dependencies, package hierarchy, roles, severity or runtime call frequency.
 */
class SourceGraphAnalyzer {
    fun analyze(
        snapshot: ProjectSourceSnapshot,
        checkCancelled: () -> Unit = {},
    ): SourceGraphSnapshot {
        val diagnostics = snapshot.diagnostics.toMutableList()
        var invalidEvidence = false
        val profile = snapshot.profile
        require(profile.maxWitnesses > 0)
        val orderedEntities = snapshot.entities.sortedWith(ENTITY_ORDER)
        val entityById = LinkedHashMap<SourceEntityId, SourceEntity>()
        for (entity in orderedEntities) {
            checkCancelled()
            val old = entityById.putIfAbsent(entity.id, entity)
            if (old != null && old != entity) {
                invalidEvidence = true
                diagnostics += SourceDiagnostic(entity.id.fileUrl, "graph", "Conflicting source declaration identity")
            }
        }
        val packageByEntity =
            entityById.mapValues { (_, entity) ->
                SourcePackageId(entity.id.moduleId, entity.packageName)
            }
        val packages =
            entityById.values
                .groupBy { packageByEntity.getValue(it.id) }
                .toSortedMap()
                .mapValues { (_, entities) -> entities.map { it.id }.sortedWith(ENTITY_ID_ORDER) }

        // Occurrence totals can exceed retained witness samples, but each edge requires an actual witness.
        val seenPairs = HashSet<Pair<SourceEntityId, SourceEntityId>>()
        val duplicatePairs = HashSet<Pair<SourceEntityId, SourceEntityId>>()
        for (relationship in snapshot.relationships) {
            checkCancelled()
            val pair = relationship.from to relationship.target
            if (!seenPairs.add(pair)) duplicatePairs.add(pair)
        }
        val evidenceByPair =
            snapshot.references
                .asSequence()
                .filter { it.target is SourceTarget.Internal }
                .groupBy { it.from to (it.target as SourceTarget.Internal).entity }
        val valid = ArrayList<ValidatedRelationship>()
        for (relationship in snapshot.relationships.sortedWith(RELATIONSHIP_ORDER)) {
            checkCancelled()
            if (relationship.from to relationship.target in duplicatePairs) {
                invalidEvidence = true
                diagnostics +=
                    SourceDiagnostic(
                        relationship.from.fileUrl,
                        "graph",
                        "Duplicate source relationship identity",
                    )
                continue
            }
            if (relationship.occurrences <= 0L ||
                relationship.from !in entityById ||
                relationship.target !in entityById
            ) {
                invalidEvidence = true
                diagnostics +=
                    SourceDiagnostic(
                        relationship.from.fileUrl,
                        "graph",
                        "Missing declaration or invalid relationship count",
                    )
                continue
            }
            val pair = relationship.from to relationship.target
            val witnesses =
                evidenceByPair[pair]
                    .orEmpty()
                    .filter { ref ->
                        ref.kind in relationship.kinds &&
                            ref.evidence in relationship.witnesses &&
                            ref.evidence.fileUrl == relationship.from.fileUrl &&
                            ref.evidence.offset >= 0 && ref.evidence.length > 0
                    }.distinct()
                    .sortedWith(REFERENCE_ORDER)
                    .take(profile.maxWitnesses)
            if (witnesses.isEmpty()) {
                invalidEvidence = true
                diagnostics +=
                    SourceDiagnostic(
                        relationship.from.fileUrl,
                        "graph",
                        "No verified source occurrence for relationship",
                    )
                continue
            }
            valid += ValidatedRelationship(relationship, witnesses)
        }
        val entities = entityById.keys.sortedWith(ENTITY_ID_ORDER)
        val entityOut = entities.associateWith { mutableSetOf<SourceEntityId>() }
        val entityIn = entities.associateWith { mutableSetOf<SourceEntityId>() }
        val entityOccurrences = HashMap<SourceEntityId, Long>()
        val entityIncomingOccurrences = HashMap<SourceEntityId, Long>()
        val packageEdges = HashMap<Pair<SourcePackageId, SourcePackageId>, PackageAccumulator>()
        for ((relation, witnesses) in valid) {
            checkCancelled()
            entityOut.getValue(relation.from).add(relation.target)
            entityIn.getValue(relation.target).add(relation.from)
            entityOccurrences[relation.from] = addCounts(entityOccurrences[relation.from] ?: 0, relation.occurrences)
            entityIncomingOccurrences[relation.target] =
                addCounts(entityIncomingOccurrences[relation.target] ?: 0, relation.occurrences)
            val fromPackage = packageByEntity.getValue(relation.from)
            val targetPackage = packageByEntity.getValue(relation.target)
            if (fromPackage == targetPackage) continue // Keep the entity edge, not a package self-edge.
            val acc = packageEdges.getOrPut(fromPackage to targetPackage) { PackageAccumulator() }
            acc.distinctPairs += relation.from to relation.target
            acc.occurrences = addCounts(acc.occurrences, relation.occurrences)
            acc.kinds += relation.kinds
            witnesses.forEach {
                acc.retainWitness(
                    SourcePackageWitness(relation.from, relation.target, it.kind, it.evidence),
                    profile.maxWitnesses,
                )
            }
        }
        val entityUses = entityOut.mapValues { (_, values) -> values.sortedWith(ENTITY_ID_ORDER) }
        val entityUsedBy = entityIn.mapValues { (_, values) -> values.sortedWith(ENTITY_ID_ORDER) }
        val entityCounts =
            entities.associateWith { id ->
                SourceDependencySummary(entityUses.getValue(id).size, entityOccurrences[id] ?: 0L)
            }
        val entityIncomingCounts =
            entities.associateWith { id ->
                SourceDependencySummary(entityUsedBy.getValue(id).size, entityIncomingOccurrences[id] ?: 0L)
            }
        val packageDeps =
            packageEdges.entries
                .map { (key, value) ->
                    SourcePackageDependency(
                        from = key.first,
                        target = key.second,
                        distinctEntityRelationships = value.distinctPairs.size,
                        occurrences = value.occurrences,
                        kinds = value.kinds.toSortedSet(compareBy { it.name }),
                        witnesses =
                            value.witnesses
                                .distinct()
                                .sortedWith(PACKAGE_WITNESS_ORDER)
                                .take(profile.maxWitnesses),
                    )
                }.sortedWith(compareBy({ it.from }, { it.target }))
        val packageOut = packages.keys.associateWith { mutableSetOf<SourcePackageId>() }
        val packageIn = packages.keys.associateWith { mutableSetOf<SourcePackageId>() }
        val packageOccurrences = HashMap<SourcePackageId, Long>()
        val packageIncomingOccurrences = HashMap<SourcePackageId, Long>()
        for (edge in packageDeps) {
            checkCancelled()
            packageOut.getValue(edge.from).add(edge.target)
            packageIn.getValue(edge.target).add(edge.from)
            packageOccurrences[edge.from] = addCounts(packageOccurrences[edge.from] ?: 0L, edge.occurrences)
            packageIncomingOccurrences[edge.target] =
                addCounts(packageIncomingOccurrences[edge.target] ?: 0L, edge.occurrences)
        }
        val uses = packageOut.mapValues { (_, targets) -> targets.sorted() }
        val usedBy = packageIn.mapValues { (_, sources) -> sources.sorted() }
        val packageCounts =
            packages.keys.associateWith { id ->
                SourceDependencySummary(uses.getValue(id).size, packageOccurrences[id] ?: 0L)
            }
        val packageIncomingCounts =
            packages.keys.associateWith { id ->
                SourceDependencySummary(usedBy.getValue(id).size, packageIncomingOccurrences[id] ?: 0L)
            }
        val edgeByPair = packageDeps.associateBy { it.from to it.target }
        val cycles =
            DirectedGraphAlgorithms
                .cyclicComponents(packages.keys, uses, checkCancelled)
                .map { members ->
                    checkCancelled()
                    val witnessNodes = DirectedGraphAlgorithms.representativeCycle(members, uses, checkCancelled)
                    val witnessEdges =
                        witnessNodes.zipWithNext().map { (from, to) ->
                            requireNotNull(edgeByPair[from to to]) { "Cycle edge has no verified evidence" }
                        }
                    SourcePackageCycle(cycleId(profile.id, members), members, witnessEdges)
                }
        val external = ArrayList<SourceExternalReference>()
        for (reference in snapshot.references) {
            checkCancelled()
            val target = reference.target as? SourceTarget.External ?: continue
            if (reference.from !in entityById ||
                reference.evidence.fileUrl != reference.from.fileUrl ||
                reference.evidence.offset < 0 ||
                reference.evidence.length <= 0
            ) {
                invalidEvidence = true
                diagnostics +=
                    SourceDiagnostic(
                        reference.from.fileUrl,
                        "graph",
                        "Invalid external reference evidence",
                    )
                continue
            }
            external += SourceExternalReference(reference.from, target.name, reference.kind, reference.evidence)
        }
        val externalEvidence =
            external.distinct().sortedWith(
                compareBy<SourceExternalReference, SourceEntityId>(ENTITY_ID_ORDER) { it.from }
                    .thenBy { it.name }
                    .thenBy { it.evidence.fileUrl }
                    .thenBy { it.evidence.offset }
                    .thenBy { it.evidence.length }
                    .thenBy { it.evidence.documentStamp }
                    .thenBy { it.kind.name },
            )
        val complete =
            snapshot.complete &&
                !snapshot.coverage.truncated &&
                snapshot.coverage.failedFiles == 0 &&
                snapshot.coverage.unresolvedOccurrences == 0L &&
                snapshot.coverage.analyzedFiles == snapshot.coverage.eligibleFiles &&
                snapshot.coverage.eligibleFiles > 0 &&
                !invalidEvidence &&
                diagnostics.isEmpty()
        checkCancelled()
        return SourceGraphSnapshot(
            profileId = profile.id,
            coverage = snapshot.coverage,
            complete = complete,
            diagnostics = diagnostics.distinct(),
            entities = entityById.values.sortedWith(ENTITY_ORDER),
            entityRelationships =
                valid.map { (relation, witnesses) ->
                    relation.copy(witnesses = witnesses.map { it.evidence }.distinct())
                },
            entityEvidence = valid.flatMap { it.witnesses }.distinct().sortedWith(REFERENCE_ORDER),
            entityUses = entityUses,
            entityUsedBy = entityUsedBy,
            entityCounts = entityCounts,
            entityIncomingCounts = entityIncomingCounts,
            packages = packages,
            packageDependencies = packageDeps,
            packageUses = uses,
            packageUsedBy = usedBy,
            packageCounts = packageCounts,
            packageIncomingCounts = packageIncomingCounts,
            externalEvidence = externalEvidence,
            externalEvidenceIsSampled = true,
            cycles = cycles,
        )
    }

    private fun addCounts(
        left: Long,
        right: Long,
    ): Long = Math.addExact(left, right)

    private class PackageAccumulator {
        val distinctPairs = HashSet<Pair<SourceEntityId, SourceEntityId>>()
        val kinds = LinkedHashSet<SourceDependencyKind>()
        val witnesses = ArrayList<SourcePackageWitness>()
        var occurrences: Long = 0

        fun retainWitness(
            candidate: SourcePackageWitness,
            limit: Int,
        ) {
            val location = witnesses.binarySearch(candidate, PACKAGE_WITNESS_ORDER)
            if (location >= 0) return
            witnesses.add(-location - 1, candidate)
            if (witnesses.size > limit) witnesses.removeAt(witnesses.lastIndex)
        }
    }

    private data class ValidatedRelationship(
        val relationship: SourceRelationship,
        val witnesses: List<SourceDependency>,
    )

    private fun cycleId(
        profileId: String,
        members: List<SourcePackageId>,
    ): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            fun field(value: String) {
                val encoded = value.toByteArray(Charsets.UTF_8)
                output.writeInt(encoded.size)
                output.write(encoded)
            }
            field(profileId)
            field("package-cycle")
            output.writeInt(members.size)
            for (member in members.sorted()) {
                field(member.moduleId)
                field(member.packageName)
            }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())
        return "package-cycle:" + digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private companion object {
        val ENTITY_ID_ORDER =
            compareBy<SourceEntityId>(
                { it.moduleId },
                { it.sourceRoot },
                { it.fileUrl },
                { it.declarationKind.name },
                { it.signature },
            )
        val ENTITY_ORDER = compareBy<SourceEntity, SourceEntityId>(ENTITY_ID_ORDER) { it.id }
        val RELATIONSHIP_ORDER =
            compareBy<SourceRelationship, SourceEntityId>(ENTITY_ID_ORDER) { it.from }
                .thenBy(ENTITY_ID_ORDER) { it.target }
        val REFERENCE_ORDER =
            compareBy<SourceDependency, SourceEntityId>(ENTITY_ID_ORDER) { it.from }
                .thenBy(ENTITY_ID_ORDER) { (it.target as SourceTarget.Internal).entity }
                .thenBy { it.evidence.fileUrl }
                .thenBy { it.evidence.offset }
                .thenBy { it.evidence.length }
                .thenBy { it.evidence.documentStamp }
                .thenBy { it.kind.name }
        val PACKAGE_WITNESS_ORDER =
            compareBy<SourcePackageWitness, SourceEntityId>(ENTITY_ID_ORDER) { it.from }
                .thenBy(ENTITY_ID_ORDER) { it.target }
                .thenBy { it.reference.fileUrl }
                .thenBy { it.reference.offset }
                .thenBy { it.reference.length }
                .thenBy { it.reference.documentStamp }
                .thenBy { it.kind.name }
    }
}
