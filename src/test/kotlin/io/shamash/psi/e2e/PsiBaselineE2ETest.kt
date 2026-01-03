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
package io.shamash.psi.e2e

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.shamash.psi.baseline.BaselineCoordinator
import io.shamash.psi.engine.Finding
import io.shamash.psi.engine.FindingSeverity
import java.nio.file.Files
import java.nio.file.Path

class PsiBaselineE2ETest : BasePlatformTestCase() {
    fun testBaselineGenerateThenUseSuppressesFindings() {
        val coordinator = BaselineCoordinator()
        // BasePlatformTestCase uses a light project where `project.basePath` can be null.
        // For baseline behavior, we only need a stable projectBase + file path under it.
        val projectBase: Path = Files.createTempDirectory("shamash-baseline-project")
        val outputDir = Files.createTempDirectory("shamash-baseline-test")
        val findings =
            listOf(
                Finding(
                    ruleId = "naming.bannedSuffixes",
                    message = "Class ends with suffix",
                    filePath = projectBase.resolve("src/main/java/a/BadService.java").toString(),
                    severity = FindingSeverity.ERROR,
                    classFqn = "a.BadService",
                ),
            )

        // GENERATE: compute + write
        val fps = coordinator.computeFingerprints(projectBase, findings)
        coordinator.writeBaseline(outputDir, fps, mergeWithExisting = false)

        // USE: load + suppress via preprocessor
        val loaded = coordinator.loadBaselineFingerprints(outputDir)
        val pre = coordinator.createSuppressionPreprocessor(loaded)
        assertNotNull("Expected baseline preprocessor to be created.", pre)

        val out = pre!!.process(projectBase, findings)
        assertTrue("Expected findings to be suppressed by baseline.", out.isEmpty())
    }
}
