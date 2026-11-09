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

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager

/** Compatibility for pre-1.0 dashboard/run shortcuts. Never invokes the configured PSI/ASM engines. */
internal object LegacyActionBridge {
    fun openFindings(project: Project) {
        if (project.isDisposed) return
        showFindings(ToolWindowManager.getInstance(project).getToolWindow("Shamash"))
    }

    /** The registered window may be unavailable or empty while IntelliJ initializes its content. */
    internal fun showFindings(window: ToolWindow?) {
        if (window == null) return
        window.activate(null, true)
        val panel =
            window.contentManager.contents
                .firstOrNull()
                ?.component as? ShamashSourcePanel
        panel?.tabs?.selectedIndex = 0
    }

    fun runSourceAnalysis(project: Project) {
        if (project.isDisposed) return
        openFindings(project)
        SourceAnalysisActions.analyze(project)
    }
}
