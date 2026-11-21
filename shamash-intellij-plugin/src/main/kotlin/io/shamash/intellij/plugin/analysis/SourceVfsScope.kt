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
package io.shamash.intellij.plugin.analysis

import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCopyEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import org.jetbrains.jps.model.java.JavaSourceRootProperties

internal class SourceVfsScope(
    roots: List<Root>,
) {
    data class Root(
        val path: String,
        val eligible: Boolean,
    )

    private val roots = roots.map { Root(normalize(it.path), it.eligible) }

    fun affects(
        path: String,
        directory: Boolean,
    ): Boolean {
        val normalized = normalize(path)
        if (directory && roots.any { it.eligible && within(it.path, normalized) }) return true
        if (!directory && !sourceFile(normalized)) return false
        return roots
            .asSequence()
            .filter { within(normalized, it.path) }
            .maxByOrNull { it.path.length }
            ?.eligible == true
    }

    fun affects(events: List<VFileEvent>): Boolean = events.any(::affects)

    private fun affects(event: VFileEvent): Boolean {
        val directory =
            when (event) {
                is VFileCreateEvent -> event.isDirectory
                else -> event.file?.isDirectory == true
            }
        return when (event) {
            is VFileMoveEvent -> {
                affects(event.oldParent.path + "/" + event.file.name, directory) ||
                    affects(event.newParent.path + "/" + event.file.name, directory)
            }

            is VFilePropertyChangeEvent -> {
                if (event.propertyName == VirtualFile.PROP_NAME) {
                    val parent = event.file.parent?.path ?: event.path.substringBeforeLast('/')
                    affects(parent + "/" + event.oldValue, directory) ||
                        affects(parent + "/" + event.newValue, directory)
                } else {
                    false
                }
            }

            is VFileCopyEvent -> {
                affects(event.newParent.path + "/" + event.newChildName, directory)
            }

            is VFileCreateEvent, is VFileDeleteEvent, is VFileContentChangeEvent -> {
                affects(event.path, directory)
            }

            else -> {
                false
            }
        }
    }

    companion object {
        fun capture(project: Project): SourceVfsScope {
            val index = ProjectRootManager.getInstance(project).fileIndex
            val roots =
                ModuleManager.getInstance(project).modules.flatMap { module ->
                    ModuleRootManager
                        .getInstance(module)
                        .contentEntries
                        .flatMap { entry ->
                            val sourceRoots =
                                entry.sourceFolders.mapNotNull { folder ->
                                    val root = folder.file ?: return@mapNotNull null
                                    val inSource = index.isInSourceContent(root)
                                    val inTest = index.isInTestSourceContent(root)
                                    val properties = folder.jpsElement.properties
                                    val generated =
                                        properties is JavaSourceRootProperties && properties.isForGeneratedSources
                                    Root(root.path, inSource && !inTest && !generated)
                                }
                            val excludedRoots =
                                entry.excludeFolders.mapNotNull { folder ->
                                    folder.file?.let { Root(it.path, false) }
                                }
                            sourceRoots + excludedRoots
                        }
                }
            return SourceVfsScope(roots)
        }

        private fun normalize(path: String): String = path.replace('\\', '/').trimEnd('/')

        private fun within(
            path: String,
            parent: String,
        ): Boolean = path == parent || path.startsWith("$parent/")

        private fun sourceFile(path: String): Boolean = path.endsWith(".java") || path.endsWith(".kt")
    }
}

internal class SourceVfsBatch(
    private val invalidate: () -> Unit,
) {
    private var matchedBefore = false

    fun before(relevant: Boolean) {
        matchedBefore = relevant
        if (relevant) invalidate()
    }

    fun after(relevant: Boolean) {
        val changed = !matchedBefore && relevant
        matchedBefore = false
        if (changed) invalidate()
    }
}
