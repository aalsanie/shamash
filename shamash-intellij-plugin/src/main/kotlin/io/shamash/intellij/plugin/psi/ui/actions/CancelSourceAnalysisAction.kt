/*
 * Copyright © 2025-2026 | Shamash
 * SPDX-License-Identifier: Apache-2.0
 */
package io.shamash.intellij.plugin.psi.ui.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import io.shamash.intellij.plugin.analysis.IdeAnalysisCoordinator

class CancelSourceAnalysisAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        e.project?.let { IdeAnalysisCoordinator.getInstance(it).cancel() }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project?.let { IdeAnalysisCoordinator.getInstance(it).state().active } == true
    }
}
