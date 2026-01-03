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
import java.nio.file.Path

/**
 * Resolves the active PSI config file for a project.
 *
 * Priority:
 * 1) If user configured an explicit path in settings, use it (relative paths are resolved from project base dir).
 * 2) Otherwise auto-discover common default locations (keeps the plugin usable out-of-the-box).
 *
 * IMPORTANT: In light tests / freshly created files, VFS may not "see" the file yet.
 * We always use refreshAndFindFileByNioFile(...) to avoid false "file not found".
 */
object ShamashPsiConfigLocator {
    /**
     * Resolve the PSI config file as a VirtualFile, or null if not found.
     */
    fun resolveConfigFile(project: Project): VirtualFile? {
        val candidates: List<Path> = buildCandidatePaths(project)

        val lfs = LocalFileSystem.getInstance()
        for (candidate in candidates) {
            val vf: VirtualFile = lfs.refreshAndFindFileByNioFile(candidate) ?: continue
            if (!vf.isDirectory) return vf
        }

        return null
    }

    /**
     * Visible for tests: compute all candidate absolute paths (in priority order).
     */
    internal fun buildCandidatePaths(project: Project): List<Path> {
        val candidates = ArrayList<Path>(32)

        // 1) Settings override (highest priority)
        val configuredRaw: String = ShamashPsiSettingsState.getInstance(project).state.configPath ?: ""
        val configured: String = configuredRaw.trim()
        if (configured.isNotBlank()) {
            val resolved: Path? = resolveConfigured(project, configured)
            if (resolved != null) {
                candidates.add(resolved)
            }
        }

        // 2) Default discovery
        val baseDirs: List<Path> = resolveProjectBaseDirs(project)

        // NOTE: "Create from Reference" currently writes to "resources/shamash/configs/psi.yml" (configs plural).
        // We support both ".../config/..." and ".../configs/..." so the locator matches both layouts.
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
                // Plain "resources" folder (your current create-from-reference behavior)
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
                candidates.add(base.resolve(rel))
            }
        }

        // De-dup while preserving order (normalize for stable equality)
        val seen = LinkedHashSet<Path>(candidates.size)
        val deduped = ArrayList<Path>(candidates.size)
        for (p in candidates) {
            val n = p.normalize()
            if (seen.add(n)) {
                deduped.add(n)
            }
        }
        return deduped
    }

    private fun resolveConfigured(
        project: Project,
        configured: String,
    ): Path? {
        val p = Path.of(configured)
        if (p.isAbsolute) return p

        // Prefer project.basePath. If missing, fall back to content roots.
        val base = project.basePath
        if (base != null) return Path.of(base).resolve(p)

        val roots = ProjectRootManager.getInstance(project).contentRoots
        if (roots.isNotEmpty()) {
            return Path.of(roots[0].path).resolve(p)
        }

        return null
    }

    /**
     * Returns possible "project base dirs" used for resolving defaults.
     * - project.basePath when available
     * - all contentRoots as fallbacks (works in test projects / atypical setups)
     */
    private fun resolveProjectBaseDirs(project: Project): List<Path> {
        val out = ArrayList<Path>(6)

        val basePath = project.basePath
        if (basePath != null) {
            out.add(Path.of(basePath))
        }

        val roots = ProjectRootManager.getInstance(project).contentRoots
        for (r in roots) {
            out.add(Path.of(r.path))
        }

        // De-dup preserve order
        val seen = LinkedHashSet<Path>(out.size)
        val deduped = ArrayList<Path>(out.size)
        for (p in out) {
            val n = p.normalize()
            if (seen.add(n)) {
                deduped.add(n)
            }
        }

        return deduped
    }
}
