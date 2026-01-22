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
import io.shamash.asm.core.config.schema.v1.model.ScanScope
import io.shamash.asm.core.scan.RunOverrides
import io.shamash.asm.core.scan.ScanOverrides
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
        /**
         * Advanced: include FactIndex in the in-memory scan result.
         *
         * Default is false to avoid keeping large graphs in memory.
         */
        var includeFactsInMemory: Boolean = false,
        /**
         * Apply scan overrides stored in this state.
         *
         * When false, overrides are ignored and YAML stays 100% in control.
         */
        var applyOverrides: Boolean = false,
        /**
         * Optional override for scan.scope.
         * Stored as a string for stable XML serialization.
         */
        var overrideScope: String? = null,
        /** Optional override for scan.followSymlinks. */
        var overrideFollowSymlinks: Boolean? = null,
        /** Optional override for scan.maxClasses. */
        var overrideMaxClasses: Int? = null,
        /** Optional override for scan.maxJarBytes. */
        var overrideMaxJarBytes: Int? = null,
        /** Optional override for scan.maxClassBytes. */
        var overrideMaxClassBytes: Int? = null,
        /**
         * Engine registry selection (optional).
         *
         * When null/blank, the built-in DefaultRuleRegistry is used.
         *
         * When set, Shamash will attempt to resolve a registry provider by this id.
         */
        var registryId: String? = null,
        /**
         * UI toggle: show extra debug/telemetry in the ASM toolwindow.
         */
        var showAdvancedTelemetry: Boolean = false,
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

    fun isIncludeFactsInMemory(): Boolean = state.includeFactsInMemory

    fun setIncludeFactsInMemory(value: Boolean) {
        state.includeFactsInMemory = value
    }

    fun isApplyOverrides(): Boolean = state.applyOverrides

    fun setApplyOverrides(value: Boolean) {
        state.applyOverrides = value
    }

    fun isShowAdvancedTelemetry(): Boolean = state.showAdvancedTelemetry

    fun setShowAdvancedTelemetry(value: Boolean) {
        state.showAdvancedTelemetry = value
    }

    fun getRegistryId(): String? = state.registryId?.trim()?.takeIf { it.isNotEmpty() }

    fun setRegistryId(id: String?) {
        state.registryId = id?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun getOverrideScope(): ScanScope? {
        val raw = state.overrideScope?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return runCatching { ScanScope.valueOf(raw) }.getOrNull()
    }

    fun setOverrideScope(scope: ScanScope?) {
        state.overrideScope = scope?.name
    }

    fun getOverrideFollowSymlinks(): Boolean? = state.overrideFollowSymlinks

    fun setOverrideFollowSymlinks(value: Boolean?) {
        state.overrideFollowSymlinks = value
    }

    fun getOverrideMaxClasses(): Int? = state.overrideMaxClasses

    fun setOverrideMaxClasses(value: Int?) {
        state.overrideMaxClasses = value
    }

    fun getOverrideMaxJarBytes(): Int? = state.overrideMaxJarBytes

    fun setOverrideMaxJarBytes(value: Int?) {
        state.overrideMaxJarBytes = value
    }

    fun getOverrideMaxClassBytes(): Int? = state.overrideMaxClassBytes

    fun setOverrideMaxClassBytes(value: Int?) {
        state.overrideMaxClassBytes = value
    }

    fun buildRunOverridesOrNull(): RunOverrides? {
        if (!isApplyOverrides()) return null

        val scan =
            ScanOverrides(
                scope = getOverrideScope(),
                followSymlinks = getOverrideFollowSymlinks(),
                maxClasses = getOverrideMaxClasses(),
                maxJarBytes = getOverrideMaxJarBytes(),
                maxClassBytes = getOverrideMaxClassBytes(),
            )

        val hasAny =
            scan.scope != null ||
                scan.followSymlinks != null ||
                scan.maxClasses != null ||
                scan.maxJarBytes != null ||
                scan.maxClassBytes != null

        return if (hasAny) RunOverrides(scan = scan) else null
    }

    companion object {
        fun getInstance(project: Project): ShamashAsmSettingsState = project.getService(ShamashAsmSettingsState::class.java)
    }
}
