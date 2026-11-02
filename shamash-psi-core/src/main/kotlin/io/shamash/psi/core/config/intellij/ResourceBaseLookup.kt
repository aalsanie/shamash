/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.psi.core.config.intellij

import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.VirtualFile
import io.shamash.psi.core.config.ProjectLayout
import java.nio.file.Path

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
