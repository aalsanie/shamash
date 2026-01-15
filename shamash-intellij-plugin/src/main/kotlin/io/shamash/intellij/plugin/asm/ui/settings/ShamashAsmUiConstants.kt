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

/**
 * ASM UI constants for the IntelliJ plugin.
 *
 * Keep these aligned with:
 * - META-INF/plugin.xml toolWindow id
 * - notificationGroups in plugin.xml
 * - action ids (if you use explicit IDs)
 */
object ShamashAsmUiConstants {
    /**
     * ToolWindow id for ASM.
     * Must match the <toolWindow id="..."> entry in plugin.xml.
     */
    const val TOOLWINDOW_ID: String = "Shamash ASM"

    /**
     * Notification group id for ASM UI notifications.
     * Must match the <notificationGroup id="..."> in plugin.xml.
     */
    const val NOTIFICATION_GROUP_ID: String = "Shamash ASM"

    /**
     * Stable key used by UI-state and settings to store/retrieve the "preferred" config path override.
     * (If you use PersistentStateComponent, this is informational; the component name is the real key.)
     */
    const val SETTINGS_COMPONENT_NAME: String = "ShamashAsmSettingsState"

    /**
     * Default tab titles (used when constructing the toolwindow tabs).
     * Keep them stable to avoid UX churn and to support UI tests later.
     */
    const val TAB_DASHBOARD: String = "Dashboard"
    const val TAB_FINDINGS: String = "Findings"
    const val TAB_CONFIG: String = "Config"
}
