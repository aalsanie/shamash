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
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile

class ProjectSourceCollector(
    private val project: Project,
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
        val index = ProjectRootManager.getInstance(project).fileIndex
        val eligibility = JvmSourceEligibility(index)
        val selected = LinkedHashMap<String, SourceFile>()
        val reasons = LinkedHashMap<String, Int>()
        var excluded = 0
        var truncated = false
        index.iterateContent { file ->
            ProgressManager.checkCanceled()
            if (file.isDirectory || file.extension !in setOf("java", "kt") || file.name.endsWith(".kts")) {
                return@iterateContent true
            }
            val decision = eligibility.evaluate(file)
            if (!decision.included) {
                excluded++
                val reason = requireNotNull(decision.exclusion).name
                reasons[reason] = (reasons[reason] ?: 0) + 1
                return@iterateContent true
            }
            if (file.url !in selected && selected.size == maxFiles) {
                truncated = true
                return@iterateContent false
            }
            selected.putIfAbsent(file.url, SourceFile(file, requireNotNull(decision.module), requireNotNull(decision.root)))
            true
        }
        return Selection(selected.values.sortedBy { it.file.url }, excluded, truncated, reasons.toSortedMap())
    }
}
