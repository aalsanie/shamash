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
package io.shamash.psi.core.intellij

import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path

/** Project basePath and content roots may be unavailable during IntelliJ fixture initialization. */
object ShamashProjectUtil {
    fun guessProjectDir(project: Project): VirtualFile? {
        val lfs = LocalFileSystem.getInstance()

        project.basePath
            ?.takeIf { it.isNotBlank() }
            ?.let { base ->
                val vf = lfs.refreshAndFindFileByNioFile(Path.of(base))
                if (vf != null && vf.isDirectory) return vf
            }

        project.projectFile
            ?.parent
            ?.takeIf { it.isValid && it.isDirectory }
            ?.let { return it }

        ProjectRootManager
            .getInstance(project)
            .contentRoots
            .firstOrNull { it.isValid && it.isDirectory }
            ?.let { return it }

        val modules = ModuleManager.getInstance(project).modules
        for (m in modules) {
            val roots = ModuleRootManager.getInstance(m).contentRoots
            roots.firstOrNull { it.isValid && it.isDirectory }?.let { return it }
        }

        return null
    }

    fun guessProjectDirPath(project: Project): Path? {
        val vf = guessProjectDir(project) ?: return null
        return try {
            Path.of(vf.path)
        } catch (_: Exception) {
            null
        }
    }
}
