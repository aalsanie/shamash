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
package io.shamash.psi.ui.actions

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import io.shamash.psi.config.ProjectLayout
import io.shamash.psi.config.ResourceBaseLookup
import io.shamash.psi.config.SchemaResources
import io.shamash.psi.ui.settings.ShamashPsiConfigLocator
import io.shamash.psi.ui.settings.ShamashPsiSettingsState
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class CreatePsiConfigFromReferenceAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val basePath = project.basePath
        if (basePath.isNullOrBlank()) {
            PsiActionUtil.notify(
                project,
                "Shamash PSI",
                "Project basePath is null; cannot create config.",
                NotificationType.ERROR,
            )
            return
        }

        // If config already exists (whatever locator resolves), open it and stop.
        val existing = ShamashPsiConfigLocator.resolveConfigFile(project)
        if (existing != null && existing.isValid) {
            PsiActionUtil.notify(
                project,
                "Shamash PSI",
                "PSI config already exists: " +
                    existing.path,
                NotificationType.INFORMATION,
            )
            openInEditor(project, existing)
            PsiActionUtil.openPsiToolWindow(project)
            return
        }

        val projectBase = Path.of(basePath)
        val target = resolveCreateTarget(project, projectBase)

        val yaml =
            SchemaResources.openReferenceYaml().use { input ->
                input.readBytes().toString(StandardCharsets.UTF_8)
            }

        try {
            Files.createDirectories(target.parent)
            if (!Files.exists(target)) {
                Files.writeString(target, yaml, StandardCharsets.UTF_8)
            }
        } catch (t: Throwable) {
            PsiActionUtil.notify(
                project,
                "Shamash PSI",
                "Failed to write config: " +
                    "${t.message}",
                NotificationType.ERROR,
            )
            return
        }

        ApplicationManager.getApplication().invokeLater {
            val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target)
            if (vf != null) {
                VfsUtil.markDirtyAndRefresh(false, false, false, vf)
                openInEditor(project, vf)
            }
        }

        PsiActionUtil.notify(
            project,
            "Shamash PSI",
            "Created PSI config at ${projectBase.relativize(target)}",
            NotificationType.INFORMATION,
        )
        PsiActionUtil.openPsiToolWindow(project)
    }

    private fun resolveCreateTarget(
        project: Project,
        projectBase: Path,
    ): Path {
        val override =
            ShamashPsiSettingsState
                .getInstance(project)
                .state.configPath
                ?.trim()
                .orEmpty()
        if (override.isNotEmpty()) {
            val p = Path.of(override)
            return if (p.isAbsolute) p else projectBase.resolve(p)
        }

        val resourceRoot =
            ResourceBaseLookup.bestResourceRootPath(project)
                ?: ResourceBaseLookup.fallbackResourceRootPath(projectBase)

        // Use ShamashPsiPaths relative (central place), but resolve it *under resources root*.
        // ShamashPsiPaths currently returns projectBase.resolve("shamash/configs/psi.yml").
        // We want resourceRoot.resolve("shamash/configs/psi.yml").
        val rel = ProjectLayout.PSI_CONFIG_RELATIVE_YML
        return resourceRoot.resolve(rel).normalize()
    }

    private fun openInEditor(
        project: Project,
        vf: com.intellij.openapi.vfs.VirtualFile,
    ) {
        FileEditorManager.getInstance(project).openFile(vf, true)
    }
}
