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

import io.shamash.intellij.plugin.asm.ui.actions.CreateAsmConfigFromReferenceAction
import io.shamash.intellij.plugin.asm.ui.settings.ShamashAsmSettingsState
import java.nio.file.Files
import java.nio.file.Path
import io.shamash.asm.core.config.ProjectLayout as AsmProjectLayout

class CreateAsmConfigFromReferenceActionE2ETest : ShamashPluginE2eTestBase() {
    fun testCreatesAsmConfigAtDefaultResourceRootWhenNoOverrideConfigured() {
        val resourcesRoot = ensureMainResourcesRoot()
        val expected = Path.of(resourcesRoot.path).resolve(AsmProjectLayout.ASM_CONFIG_RELATIVE_YML).normalize()

        assertFalse(Files.exists(expected))

        fire(CreateAsmConfigFromReferenceAction())

        assertTrue(Files.exists(expected))
        assertTrue(Files.size(expected) > 0)
    }

    fun testDoesNotOverwriteExistingAsmConfigWhenSettingsOverridePointsToIt() {
        ensureMainResourcesRoot()

        val base = Path.of(project.basePath!!)
        val overrideRel = "custom/asm.yml"
        val overrideAbs = base.resolve(overrideRel).normalize()

        val marker = "# marker: keep me\nversion: 1\n"
        writeFile(overrideAbs, marker)

        ShamashAsmSettingsState.getInstance(project).state.configPath = overrideRel

        fire(CreateAsmConfigFromReferenceAction())

        val after = Files.readString(overrideAbs)
        assertEquals(marker, after)
    }

    fun testCreatesAsmConfigAtSettingsOverrideDirectoryByAppendingAsmFileName() {
        ensureMainResourcesRoot()

        val base = Path.of(project.basePath!!)
        val overrideDirRel = "customDir"
        val overrideDirAbs = base.resolve(overrideDirRel).normalize()
        Files.createDirectories(overrideDirAbs)

        ShamashAsmSettingsState.getInstance(project).state.configPath = overrideDirRel

        fire(CreateAsmConfigFromReferenceAction())

        val expected = overrideDirAbs.resolve(AsmProjectLayout.ASM_CONFIG_FILE_YML).normalize()
        assertTrue(Files.exists(expected))
        assertTrue(Files.size(expected) > 0)
    }
}
