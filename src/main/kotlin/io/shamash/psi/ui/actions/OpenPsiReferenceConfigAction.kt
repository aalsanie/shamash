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
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.testFramework.LightVirtualFile
import io.shamash.psi.config.SchemaResources
import java.nio.charset.StandardCharsets

class OpenPsiReferenceConfigAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val text =
            try {
                SchemaResources.openReferenceYaml().use { input ->
                    input.readBytes().toString(StandardCharsets.UTF_8)
                }
            } catch (t: Throwable) {
                PsiActionUtil.notify(
                    project,
                    "Shamash PSI",
                    "Failed to load reference YAML: ${t.message}",
                    NotificationType.ERROR,
                )
                return
            }

        val vf = LightVirtualFile("shamash-psi.reference.yml", PlainTextFileType.INSTANCE, text)
        FileEditorManager.getInstance(project).openFile(vf, true)

        PsiActionUtil.openPsiToolWindow(project)
    }
}
