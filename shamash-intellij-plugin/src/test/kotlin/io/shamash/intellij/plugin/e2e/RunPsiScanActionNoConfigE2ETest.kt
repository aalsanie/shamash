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
import io.shamash.intellij.plugin.psi.ui.ShamashPsiToolWindowController
import io.shamash.intellij.plugin.psi.ui.actions.RunPsiScanAction
import io.shamash.intellij.plugin.psi.ui.actions.ShamashPsiUiStateService
import io.shamash.intellij.plugin.psi.ui.settings.ShamashPsiSettingsState

class RunPsiScanActionNoConfigE2ETest : ShamashPluginE2eTestBase() {
    fun testRunPsiScanWithoutConfigPublishesValidationErrorsAndSelectsConfigTab() {
        // Ensure no override and no default config file exists.
        ShamashPsiSettingsState.getInstance(project).state.configPath = null

        val tabs = JBTabbedPane()
        val controller = ShamashPsiToolWindowController.getInstance(project)
        controller.init(tabs)
        controller.select(ShamashPsiToolWindowController.Tab.DASHBOARD)
        assertEquals(0, tabs.selectedIndex)

        fire(RunPsiScanAction())

        // UI state updated with config-not-found error.
        val state = ShamashPsiUiStateService.getInstance(project)
        assertTrue(state.lastValidationErrors.isNotEmpty())
        val err = state.lastValidationErrors.first()
        assertTrue(err.message.contains("Config file not found", ignoreCase = true))

        // Action should land the user in Config tab.
        assertEquals(2, tabs.selectedIndex)
    }
}
