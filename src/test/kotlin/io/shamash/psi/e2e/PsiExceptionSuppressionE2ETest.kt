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
import io.shamash.psi.config.ConfigValidation
import io.shamash.psi.config.SchemaValidatorNetworkNt
import io.shamash.psi.engine.ExceptionSuppressor
import io.shamash.psi.engine.Finding
import io.shamash.psi.engine.FindingSeverity
import io.shamash.psi.engine.index.ProjectRoleIndexSnapshot
import java.io.StringReader

class PsiExceptionSuppressionE2ETest : BasePlatformTestCase() {
    fun testExceptionSuppressesASpecificRule() {
        val psi =
            myFixture.configureByText(
                "BadService.java",
                """
                package a;
                class BadService {}
                """.trimIndent(),
            )

        // This test asserts exception-based suppression; therefore the config must include an exception.
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
            exceptions:
              - id: "suppress-banned-suffix"
                reason: "Test suppression for this file/class"
                match:
                  classNameRegex: "BadService"
                suppress: ["naming.bannedSuffixes"]
            """.trimIndent()

        val validation =
            ConfigValidation.loadAndValidateV1(
                reader = StringReader(yaml),
                schemaValidator = SchemaValidatorNetworkNt,
            )
        assertTrue("Config must validate in test.", validation.ok)
        val config = requireNotNull(validation.config)

        val finding =
            Finding(
                ruleId = "naming.bannedSuffixes",
                message = "x",
                filePath = psi.virtualFile.path,
                severity = FindingSeverity.ERROR,
                classFqn = "a.BadService",
            )

        val roleIndex =
            ProjectRoleIndexSnapshot(
                roleToClasses = emptyMap(),
                classToRole = emptyMap(),
                classToAnnotations = emptyMap(),
                classToFilePath = emptyMap(),
            )

        val out = ExceptionSuppressor.apply(listOf(finding), config, roleIndex, psi)
        assertTrue("Expected finding to be suppressed by exception rule.", out.isEmpty())
    }
}
