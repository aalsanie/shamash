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
package io.shamash.intellij.plugin.psi.ui.actions

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiDocumentManager
import io.shamash.intellij.plugin.analysis.IdeAnalysisCoordinator
import io.shamash.intellij.plugin.analysis.LegacySourceBackend
import io.shamash.intellij.plugin.psi.ui.ShamashPsiToolWindowController
import io.shamash.intellij.plugin.psi.ui.settings.ShamashPsiConfigLocator
import io.shamash.psi.core.config.ValidationError
import io.shamash.psi.core.config.ValidationSeverity
import io.shamash.psi.core.scan.ShamashProjectScanRunner

class RunPsiScanAction(
    private val runner: ShamashProjectScanRunner = ShamashProjectScanRunner(),
) : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val vf = ShamashPsiConfigLocator.resolveConfigFile(project)
        if (vf == null) {
            val errors =
                listOf(
                    ValidationError(
                        path = "settings.configPath",
                        message =
                            "Config file not found. Create one from reference or place it under shamash/config/psi.yml " +
                                "(or resources/shamash/config/psi.yml), or set an explicit path in settings.",
                        severity = ValidationSeverity.ERROR,
                    ),
                )

            ShamashPsiUiStateService.getInstance(project).updateValidation(errors)

            PsiActionUtil.notify(
                project,
                "Shamash PSI",
                "No PSI config found. Create one from reference first.",
                NotificationType.WARNING,
            )

            PsiActionUtil.openPsiToolWindow(project)
            val tw = ShamashPsiToolWindowController.getInstance(project)
            tw.select(ShamashPsiToolWindowController.Tab.CONFIG)
            tw.refreshAll()
            return
        }

        // Commit in-memory editor text to PSI without flushing unrelated editor buffers to disk.
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val doc = FileDocumentManager.getInstance().getDocument(vf)
        val yaml = doc?.text ?: String(vf.contentsToByteArray())
        IdeAnalysisCoordinator.getInstance(project).analyze(LegacySourceBackend(project, yaml, runner))
    }

}
