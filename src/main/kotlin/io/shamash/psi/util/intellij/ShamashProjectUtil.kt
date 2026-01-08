/*
 * Copyright © 2025-2026 | Shamash is a refactoring tool that enforces clean architecture.
 *
 * Author: @aalsanie
 *
 * Plugin: https://plugins.jetbrains.com/plugin/29504-shamash
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
package io.shamash.psi.util.intellij

import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path

/**
 * Project directory discovery utility.
 *
 * Why this exists:
 * - Some IntelliJ test fixtures (light/heavy) may have null project.basePath.
 * - contentRoots can be empty early in setup.
 * - ProjectUtil may not be available in your chosen IntelliJ Platform dependency set.
 *
 * This helper is safe to call from background threads (it only queries metadata + VFS).
 * It uses LocalFileSystem.refreshAndFindFileByNioFile to reduce "file not found" flakiness in tests.
 */
object ShamashProjectUtil {
    /**
     * Best-effort guess of the project directory as a [VirtualFile].
     *
     * Priority:
     * 1) project.basePath
     * 2) project.projectFile.parent
     * 3) Project content roots
     * 4) Module content roots
     */
    fun guessProjectDir(project: Project): VirtualFile? {
        val lfs = LocalFileSystem.getInstance()

        // 1) basePath
        project.basePath
            ?.takeIf { it.isNotBlank() }
            ?.let { base ->
                val vf = lfs.refreshAndFindFileByNioFile(Path.of(base))
                if (vf != null && vf.isDirectory) return vf
            }

        // 2) projectFile parent (often set in test fixtures even when basePath is null)
        project.projectFile
            ?.parent
            ?.takeIf { it.isValid && it.isDirectory }
            ?.let { return it }

        // 3) project content roots
        ProjectRootManager
            .getInstance(project)
            .contentRoots
            .firstOrNull { it.isValid && it.isDirectory }
            ?.let { return it }

        // 4) module content roots (helps for multi-module + some fixtures)
        val modules = ModuleManager.getInstance(project).modules
        for (m in modules) {
            val roots = ModuleRootManager.getInstance(m).contentRoots
            roots.firstOrNull { it.isValid && it.isDirectory }?.let { return it }
        }

        return null
    }

    /**
     * Same as [guessProjectDir] but returns an absolute [Path] if possible.
     */
    fun guessProjectDirPath(project: Project): Path? {
        val vf = guessProjectDir(project) ?: return null
        return try {
            Path.of(vf.path)
        } catch (_: Exception) {
            null
        }
    }
}
