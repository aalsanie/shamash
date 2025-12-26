package io.shamash.asm.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/**
 * ASM-2: Tool window that hosts the Shamash ASM dashboard.
 */
class AsmDashboardToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = AsmDashboardPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, /* displayName = */ null, /* isLockable = */ false)
        toolWindow.contentManager.addContent(content)
    }
}
