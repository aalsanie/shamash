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
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.testFramework.LightVirtualFile
import io.shamash.asm.core.config.SchemaResources
import java.nio.charset.StandardCharsets

class OpenAsmReferenceConfigAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null && !project.isDisposed
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        if (project.isDisposed) return

        val content =
            try {
                SchemaResources.openReferenceYaml().use { input ->
                    String(input.readBytes(), StandardCharsets.UTF_8)
                }
            } catch (t: Throwable) {
                AsmActionUtil.notify(
                    project,
                    "Shamash ASM",
                    "Failed to open ASM reference YAML: ${t.message ?: t::class.java.simpleName}",
                    NotificationType.ERROR,
                )
                return
            }

        val fileType = yamlFileType()
        val vf =
            LightVirtualFile(
                "shamash-asm.reference.yml",
                fileType,
                content,
            ).apply {
                // Reference should be view-only.
                isWritable = false
            }

        // Open on UI thread.
        val app = ApplicationManager.getApplication()
        val open =
            Runnable {
                if (project.isDisposed) return@Runnable
                FileEditorManager.getInstance(project).openFile(vf, true, true)
                AsmActionUtil.notify(
                    project,
                    "Shamash ASM",
                    "Opened ASM reference configuration.",
                    NotificationType.INFORMATION,
                )
            }

        if (app.isDispatchThread) open.run() else app.invokeLater(open)
    }

    private fun yamlFileType(): FileType {
        val ftm = FileTypeManager.getInstance()
        // Prefer yml, fall back to yaml, then to plain text.
        val yml = ftm.getFileTypeByExtension("yml")
        if (!yml.isBinary) return yml
        val yaml = ftm.getFileTypeByExtension("yaml")
        if (!yaml.isBinary) return yaml
        return ftm.getFileTypeByExtension("txt")
    }
}
