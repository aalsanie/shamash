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
package io.shamash.psi.config.intellij

import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.VirtualFile
import io.shamash.psi.config.ProjectLayout
import java.nio.file.Path

/**
 * Central resolver for where Shamash PSI configs should live inside a project.
 *
 * Goal: one source of truth used by:
 *  - locator (read)
 *  - action (create)
 *
 * We prefer module src/main/resources if present, else any resources root,
 * else fallback to <projectRoot>/src/main/resources.
 */
object ResourceBaseLookup {
    fun bestResourceRootPath(project: Project): Path? {
        val modules =
            try {
                ModuleManager.getInstance(project).modules.sortedBy { it.name }
            } catch (_: Throwable) {
                emptyList()
            }

        var bestAny: Path? = null

        for (m in modules) {
            val roots =
                try {
                    ModuleRootManager.getInstance(m).sourceRoots
                } catch (_: Throwable) {
                    emptyArray()
                }

            for (vf in roots) {
                if (!looksLikeResourceRoot(vf)) continue
                val nio = vf.toNioPathOrNull() ?: continue

                val p = vf.path.replace('\\', '/').lowercase()
                if (p.contains(ProjectLayout.SRC_MAIN_RESOURCES)) return nio

                if (bestAny == null) bestAny = nio
            }
        }

        return bestAny
    }

    fun fallbackResourceRootPath(projectBase: Path): Path = projectBase.resolve(ProjectLayout.SRC_MAIN_RESOURCES).normalize()

    private fun looksLikeResourceRoot(vf: VirtualFile): Boolean {
        val p = vf.path.replace('\\', '/').lowercase()
        return p.endsWith(ProjectLayout.RESOURCES) || p.contains(ProjectLayout.SRC_MAIN_RESOURCES) ||
            p.contains(ProjectLayout.SRC_TEST_RESOURCES)
    }

    private fun VirtualFile.toNioPathOrNull(): Path? =
        try {
            this.toNioPath()
        } catch (_: Throwable) {
            null
        }
}
