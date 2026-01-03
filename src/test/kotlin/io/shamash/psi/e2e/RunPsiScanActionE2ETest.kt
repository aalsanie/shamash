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

import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.shamash.psi.config.SchemaValidator
import io.shamash.psi.config.ValidationError
import io.shamash.psi.ui.RunPsiScanAction
import io.shamash.psi.ui.actions.ShamashPsiUiStateService
import io.shamash.psi.ui.settings.ShamashPsiSettingsState
import java.util.function.BooleanSupplier

class RunPsiScanActionE2ETest : BasePlatformTestCase() {
    /**
     * Test-only validator: makes the E2E test deterministic and avoids any
     * schema loading / validator initialization complexities.
     *
     * NOTE: This does NOT change production behavior; it only removes schema validation
     * as a source of flakes for this user-flow E2E test.
     */
    private object NoopSchemaValidator : SchemaValidator {
        override fun validate(raw: Any?): List<ValidationError> = emptyList()
    }

    fun testRunPsiScanActionUpdatesUiState() {
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

        myFixture.tempDirFixture.createFile("psi-test.yml", yaml)
        ShamashPsiSettingsState.getInstance(project).state.configPath = "psi-test.yml"

        // Inject Noop validator for determinism.
        val action = RunPsiScanAction(schemaValidator = NoopSchemaValidator)

        val dataContext =
            SimpleDataContext
                .builder()
                .add(CommonDataKeys.PROJECT, project)
                .build()

        val e = TestActionEvent.createTestEvent(dataContext)
        action.actionPerformed(e)

        PlatformTestUtil.waitWithEventsDispatching(
            "PSI scan did not finish in time.",
            BooleanSupplier {
                val state = ShamashPsiUiStateService.getInstance(project)
                state.lastFindings.isNotEmpty() || state.lastValidationErrors.isNotEmpty()
            },
            20_000,
        )

        val state = ShamashPsiUiStateService.getInstance(project)

        assertTrue(
            buildString {
                append("Expected findings from action-run scan.\n")
                if (state.lastValidationErrors.isNotEmpty()) {
                    append("Validation errors were present:\n")
                    state.lastValidationErrors.forEach { ve ->
                        append("- ")
                            .append(ve.severity.name)
                            .append(" @ ")
                            .append(ve.path)
                            .append(": ")
                            .append(ve.message)
                            .append('\n')
                    }
                }
            },
            state.lastFindings.isNotEmpty(),
        )
    }
}
