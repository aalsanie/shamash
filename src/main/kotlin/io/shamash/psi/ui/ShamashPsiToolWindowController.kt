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
package io.shamash.psi.ui

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBTabbedPane
import io.shamash.psi.ui.config.ShamashPsiConfigTab
import io.shamash.psi.ui.dashboard.ShamashPsiDashboardTab
import io.shamash.psi.ui.toolbox.ShamashPsiToolboxTab

@Service(Service.Level.PROJECT)
class ShamashPsiToolWindowController(
    private val project: Project,
) {
    enum class Tab { DASHBOARD, TOOLBOX, CONFIG }

    lateinit var tabbedPane: JBTabbedPane
        private set

    lateinit var dashboardTab: ShamashPsiDashboardTab
        private set

    lateinit var toolboxTab: ShamashPsiToolboxTab
        private set

    lateinit var configTab: ShamashPsiConfigTab
        private set

    fun init(tabbedPane: JBTabbedPane) {
        this.tabbedPane = tabbedPane
        this.dashboardTab = ShamashPsiDashboardTab(project)
        this.toolboxTab = ShamashPsiToolboxTab(project)
        this.configTab = ShamashPsiConfigTab(project)

        tabbedPane.addTab("Dashboard", dashboardTab.component())
        tabbedPane.addTab("Toolbox", toolboxTab.component())
        tabbedPane.addTab("Config", configTab.component())
    }

    fun refreshAll() {
        if (::dashboardTab.isInitialized) dashboardTab.refresh()
        if (::configTab.isInitialized) configTab.refresh()
    }

    fun select(tab: Tab) {
        if (!::tabbedPane.isInitialized) return
        val index =
            when (tab) {
                Tab.DASHBOARD -> 0
                Tab.TOOLBOX -> 1
                Tab.CONFIG -> 2
            }
        if (index in 0 until tabbedPane.tabCount) {
            tabbedPane.selectedIndex = index
        }
    }

    companion object {
        fun getInstance(project: Project): ShamashPsiToolWindowController = project.getService(ShamashPsiToolWindowController::class.java)
    }
}
