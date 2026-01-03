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
import io.shamash.psi.engine.Finding
import io.shamash.psi.engine.FindingSeverity
import io.shamash.psi.engine.InlineSuppressor

@Suppress("suppressLintsFor")
class InlineSuppressionKotlinE2ETest : BasePlatformTestCase() {
    fun testKotlinAnnotationSuppressesRule() {
        val psi =
            myFixture.configureByText(
                "A.kt",
                """
                package a

                @Suppress("shamash:naming.bannedSuffixes")
                class BadService
                """.trimIndent(),
            )

        val finding =
            Finding(
                ruleId = "naming.bannedSuffixes",
                message = "x",
                filePath = psi.virtualFile.path,
                severity = FindingSeverity.ERROR,
                classFqn = "a.BadService",
            )

        val out = InlineSuppressor.apply(listOf(finding), psi)
        assertTrue("Expected finding to be suppressed by Kotlin @Suppress.", out.isEmpty())
    }

    fun testKotlinAnnotationAllSuppressesRule() {
        val psi =
            myFixture.configureByText(
                "B.kt",
                """
                package a

                @Suppress("shamash:all")
                class BadService
                """.trimIndent(),
            )

        val finding =
            Finding(
                ruleId = "packages.rolePlacement",
                message = "x",
                filePath = psi.virtualFile.path,
                severity = FindingSeverity.WARNING,
                classFqn = "a.BadService",
            )

        val out = InlineSuppressor.apply(listOf(finding), psi)
        assertTrue("Expected finding to be suppressed by Kotlin @Suppress(\"shamash:all\").", out.isEmpty())
    }

    fun testKotlinCommentSuppressesNearDeclaration() {
        val psi =
            myFixture.configureByText(
                "C.kt",
                """
                package a

                // shamash:ignore naming.bannedSuffixes
                class BadService
                """.trimIndent(),
            )

        val finding =
            Finding(
                ruleId = "naming.bannedSuffixes",
                message = "x",
                filePath = psi.virtualFile.path,
                severity = FindingSeverity.ERROR,
                classFqn = "a.BadService",
            )

        val out = InlineSuppressor.apply(listOf(finding), psi)
        assertTrue("Expected finding to be suppressed by comment directive.", out.isEmpty())
    }
}
