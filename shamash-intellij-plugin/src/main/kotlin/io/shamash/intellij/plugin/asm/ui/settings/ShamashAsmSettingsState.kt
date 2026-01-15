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
package io.shamash.intellij.plugin.asm.ui.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Per-project, persisted ASM UI settings.
 *
 * Stored in the workspace file (workspace.xml) to keep it local to the developer machine
 * and avoid committing it into VCS by default.
 */
@Service(Service.Level.PROJECT)
@State(
    name = ShamashAsmUiConstants.SETTINGS_COMPONENT_NAME,
    storages = [Storage(StoragePathMacros.WORKSPACE_FILE)],
)
class ShamashAsmSettingsState : PersistentStateComponent<ShamashAsmSettingsState.State> {
    data class State(
        /**
         * Optional explicit ASM config path override.
         *
         * If set, the ASM config locator should prefer it over auto-discovery.
         * Stored as a string for IntelliJ XML serialization stability.
         */
        var configPath: String? = null,
        /**
         * Optional last-selected tab name (future-proofing; safe to ignore if you don't use it yet).
         */
        var lastSelectedTab: String? = null,
    )

    private var state: State = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, this.state)
    }

    fun getConfigPath(): Path? {
        val raw = state.configPath?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return runCatching { Paths.get(raw).normalize() }.getOrNull()
    }

    fun setConfigPath(path: Path?) {
        state.configPath = path?.normalize()?.toString()
    }

    fun clearConfigPath() {
        state.configPath = null
    }

    fun getLastSelectedTab(): String? = state.lastSelectedTab?.trim()?.takeIf { it.isNotEmpty() }

    fun setLastSelectedTab(tab: String?) {
        state.lastSelectedTab = tab?.trim()?.takeIf { it.isNotEmpty() }
    }

    companion object {
        fun getInstance(project: Project): ShamashAsmSettingsState = project.getService(ShamashAsmSettingsState::class.java)
    }
}
