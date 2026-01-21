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
package io.shamash.intellij.plugin.asm.ui

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBTabbedPane
import io.shamash.intellij.plugin.asm.ui.config.ShamashAsmConfigTab
import io.shamash.intellij.plugin.asm.ui.dashboard.ShamashAsmDashboardTab
import io.shamash.intellij.plugin.asm.ui.facts.ShamashAsmFactsTab
import io.shamash.intellij.plugin.asm.ui.findings.ShamashAsmFindingsTab

@Service(Service.Level.PROJECT)
class ShamashAsmToolWindowController(
    private val project: Project,
) {
    enum class Tab { DASHBOARD, FINDINGS, FACTS, CONFIG }

    private val tabIndex = LinkedHashMap<Tab, Int>(8)

    lateinit var tabbedPane: JBTabbedPane
        private set

    lateinit var dashboardTab: ShamashAsmDashboardTab
        private set

    lateinit var findingsTab: ShamashAsmFindingsTab
        private set

    lateinit var factsTab: ShamashAsmFactsTab
        private set

    lateinit var configTab: ShamashAsmConfigTab
        private set

    fun init(tabbedPane: JBTabbedPane) {
        this.tabbedPane = tabbedPane

        dashboardTab = ShamashAsmDashboardTab(project)
        findingsTab = ShamashAsmFindingsTab(project)
        factsTab = ShamashAsmFactsTab(project)
        configTab = ShamashAsmConfigTab(project)

        tabbedPane.removeAll()
        tabIndex.clear()

        tabIndex[Tab.DASHBOARD] = tabbedPane.tabCount
        tabbedPane.addTab("Dashboard", dashboardTab.component())

        tabIndex[Tab.FINDINGS] = tabbedPane.tabCount
        tabbedPane.addTab("Findings", findingsTab.component())

        tabIndex[Tab.FACTS] = tabbedPane.tabCount
        tabbedPane.addTab("Facts", factsTab.component())

        tabIndex[Tab.CONFIG] = tabbedPane.tabCount
        tabbedPane.addTab("Config", configTab.component())
    }

    fun refreshAll() {
        if (::dashboardTab.isInitialized) dashboardTab.refresh()
        if (::findingsTab.isInitialized) findingsTab.refresh()
        if (::factsTab.isInitialized) factsTab.refresh()
        if (::configTab.isInitialized) configTab.refresh()
    }

    fun select(tab: Tab) {
        if (!::tabbedPane.isInitialized) return
        val index = tabIndex[tab] ?: return
        if (index in 0 until tabbedPane.tabCount) {
            tabbedPane.selectedIndex = index
        }
    }

    companion object {
        fun getInstance(project: Project): ShamashAsmToolWindowController = project.getService(ShamashAsmToolWindowController::class.java)
    }
}
