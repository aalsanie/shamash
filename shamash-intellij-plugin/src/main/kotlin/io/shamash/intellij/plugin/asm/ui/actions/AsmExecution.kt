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
package io.shamash.intellij.plugin.asm.ui.actions

import com.intellij.notification.NotificationType
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import io.shamash.asm.core.engine.ShamashAsmEngine
import io.shamash.asm.core.engine.rules.DefaultRuleRegistry
import io.shamash.intellij.plugin.ShamashPluginInfo
import io.shamash.intellij.plugin.asm.registry.AsmRuleRegistryProviders
import io.shamash.intellij.plugin.asm.ui.settings.ShamashAsmSettingsState

internal object AsmExecution {
    fun createEngine(project: Project): ShamashAsmEngine {
        val registryId = ShamashAsmSettingsState.getInstance(project).getRegistryId()
        val registry =
            if (registryId == null) {
                DefaultRuleRegistry.create()
            } else {
                val provider =
                    AsmRuleRegistryProviders.findById(registryId)
                        ?: error("Registry '$registryId' is not installed. Select an installed registry in Run Settings.")
                provider.create()
            }
        return ShamashAsmEngine(registry, "Shamash ASM", ShamashPluginInfo.version)
    }

    fun engineOrNotify(project: Project): ShamashAsmEngine? =
        try {
            createEngine(project)
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            ShamashAsmUiStateService.getInstance(project).clear()
            AsmActionUtil.notify(
                project,
                "Shamash ASM",
                "Registry initialization failed: ${e.message ?: e::class.java.simpleName}",
                NotificationType.ERROR,
            )
            null
        }
}
