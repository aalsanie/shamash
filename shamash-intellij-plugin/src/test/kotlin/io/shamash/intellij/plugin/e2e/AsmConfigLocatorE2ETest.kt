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

import io.shamash.intellij.plugin.asm.ui.settings.ShamashAsmConfigLocator
import io.shamash.intellij.plugin.asm.ui.settings.ShamashAsmSettingsState
import java.nio.file.Path
import io.shamash.asm.core.config.ProjectLayout as AsmProjectLayout

class AsmConfigLocatorE2ETest : ShamashPluginE2eTestBase() {
    fun testResolveConfigPathFindsDefaultDiscoveryPathUnderMainResourcesRoot() {
        val resourcesRootVf = ensureMainResourcesRoot()
        val base = Path.of(resourcesRootVf.path)
        val cfg = base.resolve(AsmProjectLayout.ASM_CONFIG_RELATIVE_YML).normalize()
        writeFile(cfg, "version: 1\n")

        val resolved = ShamashAsmConfigLocator.resolveConfigPath(project)
        assertNotNull(resolved)
        assertEquals(cfg.normalize(), resolved)

        val vf = ShamashAsmConfigLocator.resolveConfigFile(project)
        assertNotNull(vf)
        assertEquals(cfg.toString().replace('\\', '/'), vf!!.path.replace('\\', '/'))
    }

    fun testResolveConfigPathPrefersSettingsOverrideOverDefaults() {
        val resourcesRootVf = ensureMainResourcesRoot()
        val resourcesBase = Path.of(resourcesRootVf.path)
        writeFile(resourcesBase.resolve(AsmProjectLayout.ASM_CONFIG_RELATIVE_YML), "version: 1\n# default\n")

        val base = Path.of(project.basePath!!)
        val overrideRel = "custom/asm.yml"
        val overrideAbs = base.resolve(overrideRel).normalize()
        writeFile(overrideAbs, "version: 1\n# override\n")

        ShamashAsmSettingsState.getInstance(project).state.configPath = overrideRel

        val resolved = ShamashAsmConfigLocator.resolveConfigPath(project)
        assertNotNull(resolved)
        assertEquals(overrideAbs.normalize(), resolved)
    }
}
