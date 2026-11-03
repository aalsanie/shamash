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
package io.shamash.intellij.plugin.ui.source

import io.shamash.psi.core.source.SourceEntity
import io.shamash.psi.core.source.SourceEntityId
import io.shamash.psi.core.source.SourceExternalReference
import io.shamash.psi.core.source.SourceGraphSnapshot
import io.shamash.psi.core.source.SourcePackageCycle
import io.shamash.psi.core.source.SourcePackageDependency
import io.shamash.psi.core.source.SourcePackageId
import io.shamash.psi.core.source.SourceReference
import java.util.Locale

internal sealed interface SourceSelection {
    data class Module(val moduleId: String) : SourceSelection
    data class Package(val id: SourcePackageId) : SourceSelection
    data class Declaration(val id: SourceEntityId) : SourceSelection
}

internal data class SourceEvidence(
    val reference: SourceReference,
    val owner: SourceEntityId,
    val label: String,
)

internal data class SourceRelationRow(
    val label: String,
    val distinctTargets: Int,
    val occurrences: Long,
    val evidence: List<SourceEvidence>,
    val sampled: Boolean = false,
)

class SourceUiModel(val graph: SourceGraphSnapshot) {
    data class FindingRow(val cycle: SourcePackageCycle, val searchText: String) {
        val id: String get() = cycle.id
        val title: String get() = "Packages depend on each other"
        val summary: String get() =
            cycle.members.take(3).joinToString(", ") { qualified(it) } +
                if (cycle.members.size > 3) " · +" + (cycle.members.size - 3) + " more packages" else ""
    }

    private val cycleSearchTerms: Map<String, String> = run {
        val owner = graph.cycles.flatMap { cycle -> cycle.members.map { it to cycle.id } }.toMap()
        val indexed = HashMap<String, StringBuilder>()
        fun append(id: String, text: String) {
            indexed.getOrPut(id) { StringBuilder() }.append(' ').append(text)
        }
        graph.entities.forEach { entity ->
            val id = owner[SourcePackageId(entity.id.moduleId, entity.packageName)]
            if (id != null) append(id, entity.displayName + " " + entity.id.signature)
        }
        graph.packageDependencies.forEach { edge ->
            val id = owner[edge.from]
            if (id != null && owner[edge.target] == id) {
                edge.witnesses.forEach { append(id, it.from.signature + " " + it.target.signature) }
            }
        }
        indexed.mapValues { (_, value) -> value.toString() }
    }

    val findings: List<FindingRow> =
        graph.cycles
            .sortedWith(compareByDescending<SourcePackageCycle> { it.members.size }
                .thenBy { cycle -> cycle.members.joinToString("\u0000") { qualified(it) } })
            .map { cycle ->
                FindingRow(
                    cycle,
                    (listOf("Packages depend on each other", cycle.title) +
                        cycle.members.map(::qualified) + cycleSearchTerms[cycle.id].orEmpty())
                        .joinToString(" ").lowercase(Locale.ROOT),
                )
            }

    val declarations: Map<SourceEntityId, SourceEntity> = graph.entities.associateBy { it.id }

    val packagesByModule: Map<String, List<SourcePackageId>> =
        graph.packages.keys.groupBy { it.moduleId }
            .toSortedMap()
            .mapValues { (_, ids) -> ids.sorted() }

    val entitiesByPackage: Map<SourcePackageId, List<SourceEntity>> =
        graph.entities.groupBy { SourcePackageId(it.id.moduleId, it.packageName) }
            .mapValues { (_, values) ->
                values.sortedWith(
                    compareBy({ it.displayName.lowercase(Locale.ROOT) }, { it.id.fileUrl }, { it.id.signature }),
                )
            }

    val moduleIds: List<String> = packagesByModule.keys.sorted()
    val packageIds: List<SourcePackageId> = graph.packages.keys.sorted()

    private val outgoingEntities: Map<SourceEntityId, List<io.shamash.psi.core.source.SourceRelationship>> =
        graph.entityRelationships.groupBy { it.from }
    private val incomingEntities: Map<SourceEntityId, List<io.shamash.psi.core.source.SourceRelationship>> =
        graph.entityRelationships.groupBy { it.target }

    private val outgoingPackages: Map<SourcePackageId, List<SourcePackageDependency>> =
        graph.packageDependencies.groupBy { it.from }
    private val incomingPackages: Map<SourcePackageId, List<SourcePackageDependency>> =
        graph.packageDependencies.groupBy { it.target }

    fun filteredFindings(query: String, module: String?, pkg: SourcePackageId?): List<FindingRow> {
        val text = query.trim().lowercase(Locale.ROOT)
        return findings.filter { finding ->
            (module == null || finding.cycle.members.any { it.moduleId == module }) &&
                (pkg == null || pkg in finding.cycle.members) &&
                (text.isEmpty() || finding.searchText.contains(text))
        }
    }

    fun filteredPackages(module: String, query: String): List<SourcePackageId> {
        val text = query.trim().lowercase(Locale.ROOT)
        if (text.isEmpty()) return packagesByModule[module].orEmpty()
        return packagesByModule[module].orEmpty().filter { pkg ->
                qualified(pkg).lowercase(Locale.ROOT).contains(text) ||
                entitiesByPackage[pkg].orEmpty().any {
                    it.displayName.lowercase(Locale.ROOT).contains(text) ||
                        it.id.signature.lowercase(Locale.ROOT).contains(text)
                }
        }
    }

    fun filteredEntities(pkg: SourcePackageId, query: String): List<SourceEntity> {
        val text = query.trim().lowercase(Locale.ROOT)
        if (text.isEmpty() || qualified(pkg).lowercase(Locale.ROOT).contains(text)) {
            return entitiesByPackage[pkg].orEmpty()
        }
        return entitiesByPackage[pkg].orEmpty().filter {
            it.displayName.lowercase(Locale.ROOT).contains(text) ||
                it.id.signature.lowercase(Locale.ROOT).contains(text)
        }
    }

    internal fun uses(selection: SourceSelection): List<SourceRelationRow> = relationships(selection, incoming = false)

    internal fun usedBy(selection: SourceSelection): List<SourceRelationRow> = relationships(selection, incoming = true)

    private fun relationships(selection: SourceSelection, incoming: Boolean): List<SourceRelationRow> =
        when (selection) {
            is SourceSelection.Module -> emptyList()
            is SourceSelection.Package -> {
                val edges = if (incoming) incomingPackages[selection.id] else outgoingPackages[selection.id]
                edges.orEmpty().map { edge ->
                    val other = if (incoming) edge.from else edge.target
                    SourceRelationRow(
                        qualified(other),
                        edge.distinctEntityRelationships,
                        edge.occurrences,
                        edge.witnesses.take(MAX_WITNESSES).map {
                            SourceEvidence(it.reference, it.from, it.from.signature + " → " + it.target.signature)
                        },
                    )
                }.sortedBy { it.label }
            }
            is SourceSelection.Declaration -> {
                (if (incoming) incomingEntities[selection.id] else outgoingEntities[selection.id])
                    .orEmpty().asSequence()
                    .map { edge ->
                        val target = if (incoming) edge.from else edge.target
                        SourceRelationRow(
                            declarations[target]?.displayName?.let { it + " (" + target.moduleId + ")" }
                                ?: target.signature,
                            1,
                            edge.occurrences,
                            edge.witnesses.take(MAX_WITNESSES).map { ref ->
                                SourceEvidence(ref, edge.from, edge.from.signature + " → " + edge.target.signature)
                            },
                        )
                    }.sortedBy { it.label }.toList()
            }
        }

    internal fun external(selection: SourceSelection): List<SourceRelationRow> {
        val refs = graph.externalEvidence.filter {
            when (selection) {
                is SourceSelection.Module -> it.from.moduleId == selection.moduleId
                is SourceSelection.Package ->
                    it.from.moduleId == selection.id.moduleId &&
                        declarations[it.from]?.packageName == selection.id.packageName
                is SourceSelection.Declaration -> it.from == selection.id
            }
        }
        return refs.groupBy(SourceExternalReference::name).toSortedMap().map { (name, evidence) ->
            SourceRelationRow(
                name + " (external sample)",
                0,
                evidence.size.toLong(),
                evidence.take(MAX_WITNESSES).map {
                    SourceEvidence(it.evidence, it.from, it.from.signature + " → " + name)
                },
                sampled = true,
            )
        }
    }

    internal fun relatedCycles(selection: SourceSelection): List<SourcePackageCycle> =
        when (selection) {
            is SourceSelection.Module -> graph.cycles.filter { cycle ->
                cycle.members.any { it.moduleId == selection.moduleId }
            }
            is SourceSelection.Package -> graph.cycles.filter { selection.id in it.members }
            is SourceSelection.Declaration -> {
                val owner = declarations[selection.id]
                graph.cycles.filter { cycle ->
                    owner != null && SourcePackageId(selection.id.moduleId, owner.packageName) in cycle.members
                }
            }
        }

    internal fun scopeLabel(selection: SourceSelection): String =
        when (selection) {
            is SourceSelection.Module -> selection.moduleId + " · IDE module"
            is SourceSelection.Package -> qualified(selection.id) + " · package"
            is SourceSelection.Declaration ->
                (declarations[selection.id]?.displayName ?: selection.id.signature) +
                    " · " + selection.id.declarationKind.name.lowercase(Locale.ROOT)
        }

    internal fun summary(selection: SourceSelection): String =
        when (selection) {
            is SourceSelection.Module -> {
                val packages = packagesByModule[selection.moduleId].orEmpty()
                packages.size.toString() + " packages · " +
                    packages.sumOf { entitiesByPackage[it].orEmpty().size }.toString() + " declarations"
            }
            is SourceSelection.Package -> {
                val out = graph.packageCounts[selection.id]
                val inside = entitiesByPackage[selection.id].orEmpty().size
                inside.toString() + " declarations · " + (out?.distinctTargets ?: 0) +
                    " distinct packages used · " + (out?.occurrences ?: 0L) + " occurrences"
            }
            is SourceSelection.Declaration -> {
                val out = graph.entityCounts[selection.id]
                (out?.distinctTargets ?: 0).toString() + " distinct declarations used · " +
                    (out?.occurrences ?: 0L) + " occurrences"
            }
        }

    companion object {
        const val PAGE_SIZE = 50
        const val MAX_WITNESSES = 20

        fun qualified(id: SourcePackageId): String =
            id.moduleId + ":" + if (id.packageName.isEmpty()) "(default)" else id.packageName

        fun <T> page(items: List<T>, page: Int): List<T> {
            val start = page.coerceAtLeast(0).toLong() * PAGE_SIZE
            return if (start >= items.size) emptyList()
            else items.subList(start.toInt(), minOf(items.size, start.toInt() + PAGE_SIZE))
        }

        fun pageCount(total: Int): Int = maxOf(1, (total + PAGE_SIZE - 1) / PAGE_SIZE)
    }
}
