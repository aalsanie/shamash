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
package io.shamash.intellij.plugin.asm.ui.actions

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import io.shamash.asm.core.config.ProjectLayout
import io.shamash.asm.core.config.SchemaResources
import io.shamash.intellij.plugin.asm.ui.settings.AsmResourceBaseLookup
import io.shamash.intellij.plugin.asm.ui.settings.ShamashAsmConfigLocator
import io.shamash.intellij.plugin.asm.ui.settings.ShamashAsmSettingsState
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Creates an ASM config file from the shipped reference YAML, if one does not already exist,
 * then opens it in the editor and focuses the Shamash ASM tool window.
 */
class CreateAsmConfigFromReferenceAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val projectBase = project.basePath?.takeIf { it.isNotBlank() }?.let(Path::of)
        if (projectBase == null) {
            notify(project, "Project basePath is null; cannot create ASM config.", NotificationType.ERROR)
            return
        }

        // If config already exists (whatever locator resolves), open it and stop.
        val existing = ShamashAsmConfigLocator.resolveConfigFile(project)
        if (existing != null && existing.isValid) {
            notify(project, "ASM config already exists: ${existing.path}", NotificationType.INFORMATION)
            openInEditor(project, existing)
            AsmActionUtil.openAsmToolWindow(project)
            return
        }

        val target = resolveCreateTarget(project, projectBase)

        val yaml =
            try {
                SchemaResources.openReferenceYaml().use { input ->
                    input.readBytes().toString(StandardCharsets.UTF_8)
                }
            } catch (t: Throwable) {
                notify(project, "Failed to load reference ASM config: ${t.message}", NotificationType.ERROR)
                return
            }

        try {
            Files.createDirectories(target.parent)
            if (Files.notExists(target)) {
                Files.writeString(target, yaml, StandardCharsets.UTF_8)
            } else {
                // In case file appeared between checks.
                Files.writeString(target, yaml, StandardCharsets.UTF_8)
            }
        } catch (t: Throwable) {
            notify(project, "Failed to write ASM config: ${t.message}", NotificationType.ERROR)
            return
        }

        // Refresh VFS + open file on EDT.
        ApplicationManager.getApplication().invokeLater {
            val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target)
            if (vf != null && vf.isValid) {
                VfsUtil.markDirtyAndRefresh(false, false, false, vf)
                openInEditor(project, vf)
            }
        }

        val rel = runCatching { projectBase.relativize(target).toString() }.getOrDefault(target.toString())
        notify(project, "Created ASM config at $rel", NotificationType.INFORMATION)
        AsmActionUtil.openAsmToolWindow(project)
    }

    private fun resolveCreateTarget(
        project: Project,
        projectBase: Path,
    ): Path {
        val override =
            ShamashAsmSettingsState
                .getInstance(project)
                .state
                .configPath
                ?.trim()
                .orEmpty()

        if (override.isNotEmpty()) {
            val p = Path.of(override)
            val resolved = (if (p.isAbsolute) p else projectBase.resolve(p)).normalize()
            // If override points to a directory, create asm.yml inside it.
            return if (Files.isDirectory(resolved)) {
                resolved.resolve(ProjectLayout.ASM_CONFIG_FILE_YML).normalize()
            } else {
                resolved
            }
        }

        val resourceRoot =
            AsmResourceBaseLookup.bestResourceRootPath(project)
                ?: AsmResourceBaseLookup.fallbackResourceRootPath(projectBase)

        // Reference path is owned by asm-core config module.
        return resourceRoot.resolve(ProjectLayout.ASM_CONFIG_RELATIVE_YML).normalize()
    }

    private fun openInEditor(
        project: Project,
        vf: VirtualFile,
    ) {
        FileEditorManager.getInstance(project).openFile(vf, true)
    }

    private fun notify(
        project: Project,
        message: String,
        type: NotificationType,
    ) {
        AsmActionUtil.notify(project, "Shamash ASM", message, type)
    }
}
