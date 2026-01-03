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
package io.shamash.psi.ui.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
@State(
    name = "ShamashPsiSettings",
    storages = [Storage("shamash-psi.xml")],
)
class ShamashPsiSettingsState(
    private val project: Project,
) : PersistentStateComponent<ShamashPsiSettingsState.State> {
    data class State(
        var configPath: String? = null,
        var exportDirOverride: String? = null,
        var baselineMode: String = "OFF", // OFF | USE | GENERATE: string to avoid enum classpath issues
        var openToolWindowOnScan: Boolean = true,
    )

    private var state: State = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    companion object {
        fun getInstance(project: Project): ShamashPsiSettingsState = project.getService(ShamashPsiSettingsState::class.java)
    }
}
