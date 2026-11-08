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
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.jps.model.java.JavaSourceRootProperties

/** The indexed production-source scope, not a recursive walk of arbitrary project directories. */
class ProjectSourceCollector(private val project: Project) {
    data class SourceFile(
        val file: VirtualFile,
        val module: Module,
        val sourceRoot: VirtualFile,
    )

    data class Selection(
        val files: List<SourceFile>,
        val excluded: Int,
        val truncated: Boolean,
    )

    fun collect(maxFiles: Int): Selection {
        require(maxFiles > 0)
        val index = ProjectRootManager.getInstance(project).fileIndex
        val selected = LinkedHashMap<String, SourceFile>()
        val generatedRoots = HashMap<Pair<Module, VirtualFile>, Boolean>()
        var excluded = 0
        var truncated = false
        index.iterateContent { file ->
            ProgressManager.checkCanceled()
            if (file.isDirectory) return@iterateContent true
            if (file.extension !in setOf("java", "kt") || file.name.endsWith(".kts")) return@iterateContent true
            val module = index.getModuleForFile(file)
            val root = index.getSourceRootForFile(file)
            val eligible =
                index.isInSourceContent(file) &&
                    !index.isInTestSourceContent(file) &&
                    !index.isInLibraryClasses(file) &&
                    !index.isInLibrarySource(file) &&
                    module != null &&
                    root != null &&
                    !generatedRoots.getOrPut(module to root) { generated(module, root) }
            if (!eligible) {
                excluded++
                return@iterateContent true
            }
            if (file.url !in selected && selected.size == maxFiles) {
                truncated = true
                return@iterateContent false
            }
            selected.putIfAbsent(file.url, SourceFile(file, module!!, root!!))
            true
        }
        return Selection(selected.values.sortedBy { it.file.url }, excluded, truncated)
    }

    private fun generated(module: Module, root: VirtualFile): Boolean =
        ModuleRootManager.getInstance(module).contentEntries
            .asSequence()
            .flatMap { it.sourceFolders.asSequence() }
            .filter { it.file == root }
            .any { folder ->
                val props = folder.jpsElement.properties
                props is JavaSourceRootProperties && props.isForGeneratedSources
            }
}
