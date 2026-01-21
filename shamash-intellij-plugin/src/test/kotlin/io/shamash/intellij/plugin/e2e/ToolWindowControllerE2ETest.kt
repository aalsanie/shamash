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
package io.shamash.intellij.plugin.e2e

import com.intellij.ui.components.JBTabbedPane
import io.shamash.intellij.plugin.asm.ui.ShamashAsmToolWindowController
import io.shamash.intellij.plugin.psi.ui.ShamashPsiToolWindowController

class ToolWindowControllerE2ETest : ShamashPluginE2eTestBase() {
    fun testPsiControllerInitCreatesExpectedTabsAndSelectionWorks() {
        val tabs = JBTabbedPane()
        val controller = ShamashPsiToolWindowController.getInstance(project)
        controller.init(tabs)

        assertEquals(3, tabs.tabCount)
        assertEquals("Dashboard", tabs.getTitleAt(0))
        assertEquals("Findings", tabs.getTitleAt(1))
        assertEquals("Config", tabs.getTitleAt(2))

        controller.select(ShamashPsiToolWindowController.Tab.CONFIG)
        assertEquals(2, tabs.selectedIndex)

        controller.select(ShamashPsiToolWindowController.Tab.DASHBOARD)
        assertEquals(0, tabs.selectedIndex)
    }

    fun testAsmControllerInitCreatesExpectedTabsAndSelectionWorks() {
        val tabs = JBTabbedPane()
        val controller = ShamashAsmToolWindowController.getInstance(project)
        controller.init(tabs)

        assertEquals(4, tabs.tabCount)
        assertEquals("Dashboard", tabs.getTitleAt(0))
        assertEquals("Findings", tabs.getTitleAt(1))
        assertEquals("Config", tabs.getTitleAt(2))
        assertEquals("Facts", tabs.getTitleAt(3))

        controller.select(ShamashAsmToolWindowController.Tab.FINDINGS)
        assertEquals(1, tabs.selectedIndex)

        controller.select(ShamashAsmToolWindowController.Tab.CONFIG)
        assertEquals(2, tabs.selectedIndex)

        controller.select(ShamashAsmToolWindowController.Tab.FACTS)
        assertEquals(3, tabs.selectedIndex)
    }
}
