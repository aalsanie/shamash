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

import io.shamash.intellij.plugin.psi.ui.settings.ShamashPsiConfigLocator
import io.shamash.intellij.plugin.psi.ui.settings.ShamashPsiSettingsState
import java.nio.file.Path
import io.shamash.psi.core.config.ProjectLayout as PsiProjectLayout

class PsiConfigLocatorE2ETest : ShamashPluginE2eTestBase() {
    fun testResolveConfigFileFindsDefaultDiscoveryPathUnderProjectRoot() {
        // Put config in the most preferred repo-root location.
        val base = Path.of(project.basePath!!)
        val path = base.resolve("shamash/config/psi.yml").normalize()
        writeFile(path, "version: 1\n")

        val vf = ShamashPsiConfigLocator.resolveConfigFile(project)
        assertNotNull(vf)
        assertTrue(vf!!.path.replace('\\', '/').endsWith("/shamash/config/psi.yml"))
    }

    fun testResolveConfigFilePrefersSettingsOverrideOverDefaults() {
        val base = Path.of(project.basePath!!)

        // Default location (would be found if override didn't exist)
        writeFile(base.resolve("shamash/config/psi.yml"), "version: 1\n")

        // Override location
        val overrideRel = "custom/psi.yml"
        val overrideAbs = base.resolve(overrideRel).normalize()
        writeFile(overrideAbs, "version: 1\n# override\n")

        ShamashPsiSettingsState.getInstance(project).state.configPath = overrideRel

        val vf = ShamashPsiConfigLocator.resolveConfigFile(project)
        assertNotNull(vf)
        assertEquals(
            overrideAbs
                .normalize()
                .toString()
                .replace('\\', '/'),
            vf!!.path.replace('\\', '/'),
        )
    }

    fun testBuildCandidatePathsIncludesSettingsOverrideFirstAndUsesConfigModuleRelativeDefaults() {
        val base = Path.of(project.basePath!!)
        val overrideRel = "custom/psi.yml"
        ShamashPsiSettingsState.getInstance(project).state.configPath = overrideRel

        val candidates = ShamashPsiConfigLocator.buildCandidatePaths(project)
        assertTrue(candidates.isNotEmpty())

        // First candidate should be the configured override resolved to an absolute path.
        assertEquals(base.resolve(overrideRel).normalize(), candidates.first())

        // The config module's canonical relative path should be present (under at least one base).
        val expectedSuffix = PsiProjectLayout.PSI_CONFIG_RELATIVE_YML
        assertTrue(
            candidates.any {
                it
                    .toString()
                    .replace('\\', '/')
                    .endsWith(expectedSuffix.replace('\\', '/'))
            },
        )
    }
}
