/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.psi.core.source

import com.intellij.openapi.module.Module
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile

class ProjectSourceCollector(
    project: Project,
    private val index: ProjectFileIndex = ProjectRootManager.getInstance(project).fileIndex,
) {
    data class SourceFile(
        val file: VirtualFile,
        val module: Module,
        val sourceRoot: VirtualFile,
    )

    data class Selection(
        val files: List<SourceFile>,
        val excluded: Int,
        val truncated: Boolean,
        val excludedReasons: Map<String, Int> = emptyMap(),
    ) {
        val uncertainFiles: Int get() = excludedReasons[SourceExclusion.UNKNOWN_PLATFORM.name] ?: 0
    }

    fun collect(maxFiles: Int): Selection {
        require(maxFiles > 0)
        val eligibility = JvmSourceEligibility(index)
        val selected = LinkedHashMap<String, SourceFile>()
        val reasons = LinkedHashMap<String, Int>()
        var excluded = 0
        var truncated = false
        index.iterateContent { file ->
            ProgressManager.checkCanceled()
            if (file.isDirectory || file.extension !in setOf("java", "kt")) {
                return@iterateContent true
            }
            when (val decision = eligibility.evaluate(file)) {
                is SourceEligibility.Excluded -> {
                    excluded++
                    val reason = decision.exclusion.name
                    reasons[reason] = (reasons[reason] ?: 0) + 1
                    true
                }

                is SourceEligibility.Included -> {
                    if (file.url !in selected && selected.size == maxFiles) {
                        truncated = true
                        return@iterateContent false
                    }
                    selected.putIfAbsent(file.url, SourceFile(file, decision.module, decision.root))
                    true
                }
            }
        }
        return Selection(selected.values.sortedBy { it.file.url }, excluded, truncated, reasons.toSortedMap())
    }
}
