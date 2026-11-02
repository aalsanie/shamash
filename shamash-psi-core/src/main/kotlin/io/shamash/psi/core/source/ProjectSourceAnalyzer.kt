/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.psi.core.source

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiModificationTracker
import java.util.LinkedHashMap

/**
 * Collects per-file immutable source facts under short smart read actions. Aggregation is
 * deliberately outside PSI locks. Source/root changes fail closed rather than mixing revisions.
 */
class ProjectSourceAnalyzer(
    private val project: Project,
    private val profile: SourceAnalysisProfile = SourceAnalysisProfile(),
) {
    fun analyze(indicator: ProgressIndicator): ProjectSourceSnapshot {
        val dumb = DumbService.getInstance(project)
        val psi = PsiManager.getInstance(project)
        val rootManager = ProjectRootManager.getInstance(project)
        val projectIndex = rootManager.fileIndex
        val psiTracker = PsiModificationTracker.getInstance(project)
        val rootTracker = ProjectRootModificationTracker.getInstance(project)

        val initialPsi = psiTracker.modificationCount
        val initialRoots = rootTracker.modificationCount
        var selection: ProjectSourceCollector.Selection? = null
        dumb.runReadActionInSmartMode {
            selection = ProjectSourceCollector(project).collect(profile.maxFiles)
        }
        val scope = requireNotNull(selection)
        val diagnostics = ArrayList<SourceDiagnostic>()
        val entities = ArrayList<SourceEntity>()
        val dependencies = ArrayList<SourceDependency>()
        var failed = 0
        var analyzed = 0
        var changed = false
        var truncated = scope.truncated

        fun revisionsMatch(): Boolean =
            initialPsi == psiTracker.modificationCount && initialRoots == rootTracker.modificationCount

        val extractor =
            SourceDependencyExtractor { file ->
                val v = file.virtualFile ?: return@SourceDependencyExtractor null
                if (!projectIndex.isInSourceContent(v) || projectIndex.isInTestSourceContent(v)) {
                    return@SourceDependencyExtractor null
                }
                val module = projectIndex.getModuleForFile(v) ?: return@SourceDependencyExtractor null
                val root = projectIndex.getSourceRootForFile(v) ?: return@SourceDependencyExtractor null
                SourceDependencyExtractor.SourceLocation(module.name, root.url, v.url)
            }

        for (item in scope.files) {
            indicator.checkCanceled()
            if (!revisionsMatch()) {
                changed = true
                diagnostics += SourceDiagnostic(null, "revision", "Sources or roots changed during collection")
                break
            }
            var result: SourceDependencyExtractor.FileResult? = null
            var error: String? = null
            try {
                dumb.runReadActionInSmartMode {
                    indicator.checkCanceled()
                    val file = psi.findFile(item.file)
                    if (file == null) {
                        error = "No PSI file available for ${item.file.url}"
                    } else {
                        val location = SourceDependencyExtractor.SourceLocation(item.module.name, item.sourceRoot.url, item.file.url)
                        result = extractor.extract(file, location)
                    }
                }
                val collected = result
                if (collected == null) {
                    failed++
                    diagnostics += SourceDiagnostic(item.file.url, "extract", error ?: "Source extraction produced no result")
                } else {
                    analyzed++
                    entities += collected.entities
                    dependencies += collected.references
                    diagnostics += collected.diagnostics
                }
            } catch (cancel: ProcessCanceledException) {
                throw cancel
            } catch (t: Throwable) {
                failed++
                diagnostics += SourceDiagnostic(item.file.url, "extract", "${t.javaClass.simpleName}: ${t.message ?: "source failed"}")
            }
        }
        if (!revisionsMatch()) {
            changed = true
            diagnostics += SourceDiagnostic(null, "revision", "Sources or roots changed before publication")
        }
        // Build a bounded relationship index from value types only (no PSI access).
        val relationships = LinkedHashMap<Pair<SourceEntityId, SourceEntityId>, MutableRelationship>()
        for (dep in dependencies) {
            indicator.checkCanceled()
            val target = dep.target as? SourceTarget.Internal ?: continue
            val pair = dep.from to target.entity
            var accumulator = relationships[pair]
            if (accumulator == null) {
                if (relationships.size >= profile.maxRelationships) {
                    truncated = true
                    continue
                }
                accumulator = MutableRelationship()
                relationships[pair] = accumulator
            }
            accumulator.kinds += dep.kind
            accumulator.occurrences++
            if (accumulator.witnesses.size < profile.maxWitnesses) accumulator.witnesses += dep.evidence
        }
        if (truncated) diagnostics += SourceDiagnostic(null, "limit", "Source analysis reached an internal resource bound")
        val unresolved = dependencies.count { it.target is SourceTarget.Unresolved }.toLong()
        val complete = !truncated && !changed && failed == 0 && unresolved == 0L && diagnostics.isEmpty()
        return ProjectSourceSnapshot(
            profile = profile,
            entities = entities.distinctBy { it.id }.sortedWith(compareBy({ it.id.moduleId }, { it.id.fileUrl }, { it.offset })),
            references = dependencies.sortedWith(compareBy({ it.from.fileUrl }, { it.evidence.offset }, { it.target.toString() })),
            relationships =
                relationships.map { (key, value) ->
                    SourceRelationship(key.first, key.second, value.kinds.toSet(), value.occurrences, value.witnesses.toList())
                }.sortedWith(compareBy({ it.from.moduleId }, { it.from.fileUrl }, { it.toString() })),
            diagnostics = diagnostics.distinct(),
            coverage = SourceCoverage(
                eligibleFiles = scope.files.size,
                analyzedFiles = analyzed,
                failedFiles = failed,
                resolvedOccurrences = dependencies.count { it.target !is SourceTarget.Unresolved }.toLong(),
                unresolvedOccurrences = unresolved,
                excludedFiles = scope.excluded,
                truncated = truncated || changed,
            ),
            complete = complete,
        )
    }

    private class MutableRelationship {
        val kinds = LinkedHashSet<SourceDependencyKind>()
        val witnesses = ArrayList<SourceReference>()
        var occurrences = 0L
    }
}
