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
package io.shamash.psi.ui.settings

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import io.shamash.psi.util.intellij.ShamashProjectUtil
import java.nio.file.Path

/**
 * Resolves the active PSI config file for a project.
 *
 * Priority:
 * 1) Settings override (relative resolved from a stable project base dir).
 * 2) Default discovery in common locations under plausible base dirs.
 *
 * Uses refreshAndFindFileByNioFile(...) to avoid stale VFS states (especially in tests).
 */
object ShamashPsiConfigLocator {
    fun resolveConfigFile(project: Project): VirtualFile? {
        val candidates = buildCandidatePaths(project)
        if (candidates.isEmpty()) return null

        val lfs = LocalFileSystem.getInstance()
        for (candidate in candidates) {
            val vf = lfs.refreshAndFindFileByNioFile(candidate) ?: continue
            if (!vf.isDirectory && vf.isValid) return vf
        }
        return null
    }

    /**
     * Visible for tests: compute candidate absolute paths (priority order).
     */
    internal fun buildCandidatePaths(project: Project): List<Path> {
        val candidates = ArrayList<Path>(32)

        // 1) Settings override
        val configured =
            ShamashPsiSettingsState
                .getInstance(project)
                .state
                .configPath
                ?.trim()
                .orEmpty()

        if (configured.isNotBlank()) {
            resolveConfigured(project, configured)?.let { candidates.add(it.normalize()) }
        }

        // 2) Default discovery
        val baseDirs = resolveProjectBaseDirs(project)
        if (baseDirs.isEmpty()) return candidates

        val relativeDefaults: List<String> =
            listOf(
                // Preferred documented paths (repo root)
                "shamash/config/psi.yml",
                "shamash/config/psi.yaml",
                "shamash/configs/psi.yml",
                "shamash/configs/psi.yaml",
                // Resources (common Gradle/Maven layouts)
                "src/main/resources/shamash/config/psi.yml",
                "src/main/resources/shamash/config/psi.yaml",
                "src/main/resources/shamash/configs/psi.yml",
                "src/main/resources/shamash/configs/psi.yaml",
                "src/resources/shamash/config/psi.yml",
                "src/resources/shamash/config/psi.yaml",
                "src/resources/shamash/configs/psi.yml",
                "src/resources/shamash/configs/psi.yaml",
                // Plain "resources" folder
                "resources/shamash/config/psi.yml",
                "resources/shamash/config/psi.yaml",
                "resources/shamash/configs/psi.yml",
                "resources/shamash/configs/psi.yaml",
                // Other tolerable alternates
                "shamash/psi.yml",
                "shamash/psi.yaml",
                ".shamash/psi.yml",
                ".shamash/psi.yaml",
                "psi.yml",
                "psi.yaml",
            )

        for (base in baseDirs) {
            for (rel in relativeDefaults) {
                candidates.add(base.resolve(rel).normalize())
            }
        }

        // De-dup while preserving order.
        val seen = LinkedHashSet<Path>(candidates.size)
        val deduped = ArrayList<Path>(candidates.size)
        for (p in candidates) {
            if (seen.add(p)) deduped.add(p)
        }
        return deduped
    }

    private fun resolveConfigured(
        project: Project,
        configured: String,
    ): Path? {
        val p = Path.of(configured)
        if (p.isAbsolute) return p

        // Prefer stable base dirs that don't require Gradle model interaction.
        project.basePath?.let { return Path.of(it).resolve(p) }

        ShamashProjectUtil.guessProjectDir(project)?.path?.let { return Path.of(it).resolve(p) }

        // Last resort: content roots (may trigger model init in some environments).
        val roots = runCatching { ProjectRootManager.getInstance(project).contentRoots }.getOrDefault(emptyArray())
        if (roots.isNotEmpty()) {
            return Path.of(roots[0].path).resolve(p)
        }

        return null
    }

    /**
     * Possible base dirs used for default discovery:
     * - project.basePath
     * - guessProjectDir(project) (important for tests)
     * - content roots (last resort)
     */
    private fun resolveProjectBaseDirs(project: Project): List<Path> {
        val out = ArrayList<Path>(8)

        project.basePath?.let { out.add(Path.of(it).normalize()) }

        ShamashProjectUtil.guessProjectDir(project)?.path?.let { out.add(Path.of(it).normalize()) }

        // Only touch ProjectRootManager as a fallback.
        val roots = runCatching { ProjectRootManager.getInstance(project).contentRoots }.getOrDefault(emptyArray())
        for (r in roots) {
            out.add(Path.of(r.path).normalize())
        }

        // De-dup preserve order.
        val seen = LinkedHashSet<Path>(out.size)
        val deduped = ArrayList<Path>(out.size)
        for (p in out) {
            if (seen.add(p)) deduped.add(p)
        }
        return deduped
    }
}
