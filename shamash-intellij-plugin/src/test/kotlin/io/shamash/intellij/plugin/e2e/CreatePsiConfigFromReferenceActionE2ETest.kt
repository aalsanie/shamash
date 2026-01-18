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

import io.shamash.intellij.plugin.psi.ui.actions.CreatePsiConfigFromReferenceAction
import io.shamash.intellij.plugin.psi.ui.settings.ShamashPsiSettingsState
import java.nio.file.Files
import java.nio.file.Path
import io.shamash.psi.core.config.ProjectLayout as PsiProjectLayout

class CreatePsiConfigFromReferenceActionE2ETest : ShamashPluginE2eTestBase() {
    fun testCreatesPsiConfigAtDefaultResourceRootWhenNoOverrideConfigured() {
        val resourcesRoot = ensureMainResourcesRoot()
        val expected = Path.of(resourcesRoot.path).resolve(PsiProjectLayout.PSI_CONFIG_RELATIVE_YML).normalize()

        assertFalse(Files.exists(expected))

        fire(CreatePsiConfigFromReferenceAction())

        assertTrue(Files.exists(expected))
        assertTrue(Files.size(expected) > 0)
    }

    fun testDoesNotOverwriteExistingPsiConfigWhenLocatorResolvesIt() {
        val resourcesRoot = ensureMainResourcesRoot()
        val existing = Path.of(resourcesRoot.path).resolve(PsiProjectLayout.PSI_CONFIG_RELATIVE_YML).normalize()

        val marker = "# marker: keep me\nversion: 1\n"
        writeFile(existing, marker)

        fire(CreatePsiConfigFromReferenceAction())

        val after = Files.readString(existing)
        assertEquals(marker, after)
    }

    fun testCreatesPsiConfigAtSettingsOverridePathWhenConfigured() {
        ensureMainResourcesRoot()

        val base = Path.of(project.basePath!!)
        val overrideRel = "custom/psi.yml"
        val overrideAbs = base.resolve(overrideRel).normalize()

        ShamashPsiSettingsState.getInstance(project).state.configPath = overrideRel

        fire(CreatePsiConfigFromReferenceAction())

        assertTrue(Files.exists(overrideAbs))
        assertTrue(Files.size(overrideAbs) > 0)
    }
}
