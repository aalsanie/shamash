/*
 * Copyright © 2025-2026 | Shamash
 *
 * Shamash is a JVM architecture enforcement tool that helps teams
 * define, validate, and continuously enforce architectural boundaries.
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
package io.shamash.intellij.plugin.asm.ui.settings

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import io.shamash.asm.core.config.ProjectLayout
import java.nio.file.Files
import java.nio.file.Path

/**
 * Resolves the active ASM config file/path for a project.
 *
 * Priority:
 * 1) Settings override (relative resolved from project basePath).
 * 2) Default discovery in common locations under plausible base dirs.
 *
 * Default discovery uses:
 * - module resource roots (best first)
 * - fallback resource root (<projectRoot>/src/main/resources)
 * - project root
 *
 * Candidate relative paths are primarily owned by asm-core: [ProjectLayout.ASM_CONFIG_CANDIDATES].
 * We also accept a small set of pragmatic alternates (e.g. asm.yml at project root) to match PSI's
 * "smart" discovery behavior.
 */
object ShamashAsmConfigLocator {
    fun resolveConfigFile(project: Project): VirtualFile? {
        val p = resolveConfigPath(project) ?: return null
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(p)
        return if (vf != null && vf.isValid && !vf.isDirectory) vf else null
    }

    fun resolveConfigPath(project: Project): Path? {
        val projectBase = project.basePath?.takeIf { it.isNotBlank() }?.let(Path::of) ?: return null

        // 1) Settings override
        val configured =
            ShamashAsmSettingsState
                .getInstance(project)
                .state
                .configPath
                ?.trim()
                .orEmpty()

        if (configured.isNotBlank()) {
            resolveConfigured(projectBase, configured)?.let { configuredPath ->
                val file = normalizeIfDirectory(configuredPath)
                if (file != null && Files.isRegularFile(file)) return file
            }
        }

        // 2) Default discovery (resource roots first)
        val bases = LinkedHashSet<Path>(8)
        AsmResourceBaseLookup.bestResourceRootPath(project)?.let { bases.add(it.normalize()) }
        bases.add(AsmResourceBaseLookup.fallbackResourceRootPath(projectBase).normalize())
        bases.add(projectBase.normalize())

        val relativeCandidates = buildRelativeCandidates()
        for (base in bases) {
            for (rel in relativeCandidates) {
                val candidate = base.resolve(rel).normalize()
                if (Files.isRegularFile(candidate)) return candidate
            }
        }

        return null
    }

    private fun buildRelativeCandidates(): List<String> {
        val out = ArrayList<String>(32)

        // Primary (owned by asm-core)
        out.addAll(ProjectLayout.ASM_CONFIG_CANDIDATES)

        // Pragmatic alternates (match PSI-like discovery expectations)
        out.add("asm.yml")
        out.add("asm.yaml")
        out.add("shamash/asm.yml")
        out.add("shamash/asm.yaml")
        out.add(".shamash/asm.yml")
        out.add(".shamash/asm.yaml")
        out.add("shamash/config/asm.yml")
        out.add("shamash/config/asm.yaml")
        out.add("shamash/configs/asm.yml")
        out.add("shamash/configs/asm.yaml")

        // De-dup preserve order
        val seen = LinkedHashSet<String>(out.size)
        val deduped = ArrayList<String>(out.size)
        for (p in out) {
            if (seen.add(p)) deduped.add(p)
        }
        return deduped
    }

    private fun resolveConfigured(
        projectBase: Path,
        configured: String,
    ): Path? =
        try {
            val p = Path.of(configured)
            (if (p.isAbsolute) p else projectBase.resolve(p)).normalize()
        } catch (_: Throwable) {
            null
        }

    private fun normalizeIfDirectory(path: Path?): Path? {
        if (path == null) return null
        return try {
            if (Files.isDirectory(path)) {
                path.resolve(ProjectLayout.ASM_CONFIG_FILE_YML).normalize()
            } else {
                path
            }
        } catch (_: Throwable) {
            path
        }
    }
}
