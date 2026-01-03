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
import io.shamash.psi.baseline.BaselineConfig
import io.shamash.psi.config.ConfigValidation
import io.shamash.psi.config.SchemaValidatorNetworkNt
import io.shamash.psi.scan.ShamashProjectScanRunner
import io.shamash.psi.scan.ShamashScanOptions
import io.shamash.psi.ui.actions.ShamashPsiUiStateService
import java.io.StringReader

@Suppress("suppressLintsFor")
class PsiScanE2ETest : BasePlatformTestCase() {
    fun testEndToEndScanProducesFindingsAndUpdatesUiState() {
        myFixture.tempDirFixture.createFile(
            "a/BadService.java",
            """
            package a;
            class BadService {}
            """.trimIndent(),
        )

        val yaml =
            """
            version: 1
            project:
              validation:
                unknownRuleId: WARN
            roles: {}
            rules:
              naming.bannedSuffixes:
                enabled: true
                severity: WARNING
                params:
                  banned: ["Service"]
            exceptions: []
            """.trimIndent()

        val validation =
            ConfigValidation.loadAndValidateV1(
                reader = StringReader(yaml),
                schemaValidator = SchemaValidatorNetworkNt,
            )
        assertTrue("Config must validate in test.", validation.ok)
        val config = requireNotNull(validation.config)

        val runner = ShamashProjectScanRunner()
        val result =
            runner.scanProject(
                project = project,
                config = config,
                options =
                    ShamashScanOptions(
                        exportReports = false,
                        baseline = BaselineConfig.Off,
                        toolName = "Shamash PSI",
                        toolVersion = "test",
                        generatedAtEpochMillis = System.currentTimeMillis(),
                    ),
            )

        assertTrue("Expected at least one finding from naming.bannedSuffixes.", result.findings.isNotEmpty())

        val state = ShamashPsiUiStateService.getInstance(project)
        state.updateFindings(result.findings)

        assertTrue("UI state must reflect scan findings.", state.lastFindings.isNotEmpty())
    }
}
