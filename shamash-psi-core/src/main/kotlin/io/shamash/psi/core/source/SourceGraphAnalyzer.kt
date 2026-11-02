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
        val packageByEntity = entityById.mapValues { (_, entity) ->
            SourcePackageId(entity.id.moduleId, entity.packageName)
        }
        val packages =
            entityById.values.groupBy { packageByEntity.getValue(it.id) }
                .toSortedMap()
                .mapValues { (_, entities) -> entities.map { it.id }.sortedWith(ENTITY_ID_ORDER) }

        // PR8 retains bounded witness locations, but full occurrence counts in relationships.
        // A declared relationship is usable only when at least one matching source occurrence exists.
        val evidenceByPair =
            snapshot.references.asSequence()
                .filter { it.target is SourceTarget.Internal }
                .groupBy { it.from to (it.target as SourceTarget.Internal).entity }
        val valid = ArrayList<ValidatedRelationship>()
        for (relationship in snapshot.relationships.sortedWith(RELATIONSHIP_ORDER)) {
            checkCancelled()
            if (relationship.occurrences <= 0L ||
                relationship.from !in entityById ||
                relationship.target !in entityById
            ) {
                invalidEvidence = true
                diagnostics += SourceDiagnostic(
                    relationship.from.fileUrl, "graph", "Missing declaration or invalid relationship count",
                )
                continue
            }
            val pair = relationship.from to relationship.target
            val witnesses =
                evidenceByPair[pair].orEmpty()
                    .filter { ref ->
                        ref.kind in relationship.kinds &&
                            ref.evidence in relationship.witnesses &&
                            ref.evidence.fileUrl == relationship.from.fileUrl &&
                            ref.evidence.offset >= 0 && ref.evidence.length > 0
                    }
                    .distinct()
                    .sortedWith(REFERENCE_ORDER)
                    .take(profile.maxWitnesses)
            if (witnesses.isEmpty()) {
                invalidEvidence = true
                diagnostics += SourceDiagnostic(
                    relationship.from.fileUrl, "graph", "No verified source occurrence for relationship",
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
            packageEdges.entries.map { (key, value) ->
                SourcePackageDependency(
                    from = key.first,
                    target = key.second,
                    distinctEntityRelationships = value.distinctPairs.size,
                    occurrences = value.occurrences,
                    kinds = value.kinds.toSortedSet(compareBy { it.name }),
                    witnesses = value.witnesses.distinct().sortedWith(PACKAGE_WITNESS_ORDER).take(profile.maxWitnesses),
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
            DirectedGraphAlgorithms.cyclicComponents(packages.keys, uses, checkCancelled)
                .map { members ->
                    checkCancelled()
                    val witnessNodes = DirectedGraphAlgorithms.representativeCycle(members, uses, checkCancelled)
                    val witnessEdges =
                        witnessNodes.zipWithNext().map { (from, to) ->
                            requireNotNull(edgeByPair[from to to]) { "Cycle edge has no verified evidence" }
                        }
                    SourcePackageCycle(cycleId(profile.id, members), members, witnessEdges)
                }
        val external =
            snapshot.references.asSequence()
                .mapNotNull { ref ->
                    val target = ref.target as? SourceTarget.External ?: return@mapNotNull null
                    if (ref.from !in entityById) return@mapNotNull null
                    SourceExternalReference(ref.from, target.name, ref.kind, ref.evidence)
                }
                .distinct()
                .sortedWith(compareBy({ it.from.moduleId }, { it.from.fileUrl }, { it.name }, { it.evidence.offset }))
                .toList()
        val complete =
            snapshot.complete &&
                !snapshot.coverage.truncated &&
                snapshot.coverage.failedFiles == 0 &&
                snapshot.coverage.unresolvedOccurrences == 0L &&
                snapshot.coverage.analyzedFiles == snapshot.coverage.eligibleFiles &&
                snapshot.coverage.eligibleFiles > 0 &&
                !invalidEvidence &&
                diagnostics.isEmpty()
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
            externalEvidence = external,
            externalEvidenceIsSampled = true,
            cycles = cycles,
        )
    }

    private fun addCounts(left: Long, right: Long): Long = Math.addExact(left, right)

    private class PackageAccumulator {
        val distinctPairs = HashSet<Pair<SourceEntityId, SourceEntityId>>()
        val kinds = LinkedHashSet<SourceDependencyKind>()
        val witnesses = ArrayList<SourcePackageWitness>()
        var occurrences: Long = 0

        fun retainWitness(candidate: SourcePackageWitness, limit: Int) {
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

    private fun cycleId(profileId: String, members: List<SourcePackageId>): String {
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
        val RELATIONSHIP_ORDER = compareBy<SourceRelationship, SourceEntityId>(ENTITY_ID_ORDER) { it.from }
            .thenBy(ENTITY_ID_ORDER) { it.target }
        val REFERENCE_ORDER =
            compareBy<SourceDependency>(
                { it.evidence.fileUrl },
                { it.evidence.offset },
                { it.evidence.length },
                { it.evidence.documentStamp },
                { it.kind.name },
            )
        val PACKAGE_WITNESS_ORDER =
            compareBy<SourcePackageWitness, SourceEntityId>(ENTITY_ID_ORDER) { it.from }
                .thenBy(ENTITY_ID_ORDER) { it.target }
                .thenBy { it.reference.fileUrl }
                .thenBy { it.reference.offset }
                .thenBy { it.kind.name }
    }
}
