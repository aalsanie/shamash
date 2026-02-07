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
import io.shamash.intellij.plugin.asm.ui.actions.ShamashAsmUiStateService
import io.shamash.intellij.plugin.asm.ui.actions.ValidateAsmConfigAction
import io.shamash.intellij.plugin.asm.ui.settings.ShamashAsmSettingsState

class ValidateAsmConfigActionNoConfigE2ETest : ShamashPluginE2eTestBase() {
    fun testValidateAsmConfigWithoutConfigClearsStateAndSelectsConfigTab() {
        // Ensure no override and no default config file exists.
        ShamashAsmSettingsState.getInstance(project).state.configPath = null

        val tabs = JBTabbedPane()
        val controller = ShamashAsmToolWindowController.getInstance(project)
        controller.init(tabs)
        controller.select(ShamashAsmToolWindowController.Tab.DASHBOARD)
        assertEquals(0, tabs.selectedIndex)

        // Seed state to ensure the action clears it.
        val uiState = ShamashAsmUiStateService.getInstance(project)
        uiState.update(configPath = null, scanResult = null)
        assertNotNull(uiState.getState())

        fire(ValidateAsmConfigAction())

        assertNull(uiState.getState())
        assertEquals(2, tabs.selectedIndex)
    }
}
