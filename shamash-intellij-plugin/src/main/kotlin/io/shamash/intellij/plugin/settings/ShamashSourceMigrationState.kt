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
package io.shamash.intellij.plugin.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.project.Project

/**
 * Records that the source-only V1 experience was opened. The old PSI/ASM settings components,
 * YAML files, baselines and reports are intentionally neither loaded nor rewritten here.
 * IntelliJ owns tool-window position; do not modify the persisted docking state.
 */
@Service(Service.Level.PROJECT)
@State(
    name = "ShamashSourceMigration",
    storages = [Storage(StoragePathMacros.WORKSPACE_FILE)],
)
class ShamashSourceMigrationState : PersistentStateComponent<ShamashSourceMigrationState.State> {
    data class State(
        var acknowledgedVersion: Int = 0,
    )

    private var stored = State()

    override fun getState(): State = stored

    override fun loadState(state: State) {
        stored = state.copy()
    }

    fun acknowledge(): Boolean {
        if (stored.acknowledgedVersion >= CURRENT_VERSION) return false
        stored.acknowledgedVersion = CURRENT_VERSION
        return true
    }

    companion object {
        const val CURRENT_VERSION: Int = 1

        fun getInstance(project: Project): ShamashSourceMigrationState =
            project.getService(ShamashSourceMigrationState::class.java)
    }
}
