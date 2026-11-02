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

/**
 * Collects per-file immutable source facts under short smart read actions. Aggregation is
 * deliberately outside PSI locks. Source/root changes fail closed rather than mixing revisions.
 */
class SourceRevisionChangedException : IllegalStateException("Source or root revision changed during analysis")

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
        val allowedFiles = scope.files.mapTo(HashSet()) { it.file.url }
        val diagnostics = ArrayList<SourceDiagnostic>()
        val entities = ArrayList<SourceEntity>()
        val relationships = SourceRelationshipAccumulator(profile)
        var failed = 0
        var analyzed = 0
        var changed = false
        var truncated = scope.truncated

        fun revisionsMatch(): Boolean =
            initialPsi == psiTracker.modificationCount && initialRoots == rootTracker.modificationCount

        val extractor =
            SourceDependencyExtractor { file ->
                val v = file.virtualFile ?: return@SourceDependencyExtractor null
                if (v.url !in allowedFiles ||
                    !projectIndex.isInSourceContent(v) ||
                    projectIndex.isInTestSourceContent(v)
                ) {
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
                    collected.references.forEach(relationships::add)
                    diagnostics += collected.diagnostics
                }
            } catch (cancel: ProcessCanceledException) {
                throw cancel
            } catch (t: Throwable) {
                failed++
                diagnostics += SourceDiagnostic(item.file.url, "extract", "${t.javaClass.simpleName}: ${t.message ?: "source failed"}")
            }
        }
        if (!revisionsMatch()) changed = true
        if (changed) throw SourceRevisionChangedException()
        if (!revisionsMatch()) throw SourceRevisionChangedException()
        truncated = truncated || relationships.truncated
        if (truncated) diagnostics += SourceDiagnostic(null, "limit", "Source analysis reached an internal resource bound")
        val unresolved = relationships.unresolvedOccurrences
        val complete = !truncated && failed == 0 && unresolved == 0L && diagnostics.isEmpty()
        return ProjectSourceSnapshot(
            profile = profile,
            entities = entities.distinctBy { it.id }.sortedWith(compareBy({ it.id.moduleId }, { it.id.fileUrl }, { it.offset })),
            references = relationships.evidence(),
            relationships = relationships.relationships(),
            diagnostics = diagnostics.distinct(),
            coverage = SourceCoverage(
                eligibleFiles = scope.files.size,
                analyzedFiles = analyzed,
                failedFiles = failed,
                resolvedOccurrences = relationships.resolvedOccurrences,
                unresolvedOccurrences = unresolved,
                excludedFiles = scope.excluded,
                truncated = truncated,
            ),
            complete = complete,
        )
    }

}
