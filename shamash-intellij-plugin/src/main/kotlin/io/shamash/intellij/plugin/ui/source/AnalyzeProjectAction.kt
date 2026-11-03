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
package io.shamash.intellij.plugin.ui.source

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import io.shamash.intellij.plugin.analysis.IdeAnalysisCoordinator
import io.shamash.intellij.plugin.analysis.ProjectSourceBackend

internal object SourceAnalysisActions {
    fun analyze(project: Project): Boolean {
        if (project.isDisposed || !ApplicationManager.getApplication().isDispatchThread) return false
        val coordinator = IdeAnalysisCoordinator.getInstance(project)
        if (coordinator.state().active) return false
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        return coordinator.analyze(ProjectSourceBackend(project, coordinator)) != null
    }
}

class AnalyzeProjectAction : AnAction() {
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project?.let {
            !it.isDisposed && !IdeAnalysisCoordinator.getInstance(it).state().active
        } == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        e.project?.let(SourceAnalysisActions::analyze)
    }
}
