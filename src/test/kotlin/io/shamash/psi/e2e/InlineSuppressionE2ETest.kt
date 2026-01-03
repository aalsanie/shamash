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

class InlineSuppressionE2ETest : BasePlatformTestCase() {
    fun testCommentFilewideSuppressesRuleWhenOnLineZero() {
        val psi =
            myFixture.configureByText(
                "A.java",
                """
                // shamash:ignore naming.bannedSuffixes
                package a;
                class MyService {}
                """.trimIndent(),
            )

        val finding =
            Finding(
                ruleId = "naming.bannedSuffixes",
                message = "x",
                filePath = psi.virtualFile.path,
                severity = FindingSeverity.ERROR,
                classFqn = "a.MyService",
            )

        val out = InlineSuppressor.apply(listOf(finding), psi)
        assertTrue("Expected finding to be suppressed file-wide (line 0 directive).", out.isEmpty())
    }

    fun testCommentAllSuppressesEverything() {
        val psi =
            myFixture.configureByText(
                "B.java",
                """
                // shamash:ignore all
                package a;
                class X {}
                """.trimIndent(),
            )

        val f1 =
            Finding(
                ruleId = "naming.bannedSuffixes",
                message = "x",
                filePath = psi.virtualFile.path,
                severity = FindingSeverity.ERROR,
                classFqn = "a.X",
            )
        val f2 =
            Finding(
                ruleId = "packages.rolePlacement",
                message = "y",
                filePath = psi.virtualFile.path,
                severity = FindingSeverity.WARNING,
                classFqn = "a.X",
            )

        val out = InlineSuppressor.apply(listOf(f1, f2), psi)
        assertTrue("Expected all findings to be suppressed by 'all'.", out.isEmpty())
    }

    fun testCommentLocalSuppressesRuleWhenNearDeclaration() {
        val psi =
            myFixture.configureByText(
                "C.java",
                """
                package a;

                // shamash:ignore naming.bannedSuffixes
                class BadService {}
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
        assertTrue("Expected finding to be suppressed by nearby comment directive.", out.isEmpty())
    }

    fun testJavaAnnotationSuppressesRule() {
        val psi =
            myFixture.configureByText(
                "D.java",
                """
                package a;

                @SuppressWarnings("shamash:naming.bannedSuffixes")
                class BadService {}
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
        assertTrue("Expected finding to be suppressed by @SuppressWarnings.", out.isEmpty())
    }

    fun testJavaAnnotationAllSuppressesRule() {
        val psi =
            myFixture.configureByText(
                "E.java",
                """
                package a;

                @SuppressWarnings("shamash:all")
                class BadService {}
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
        assertTrue("Expected finding to be suppressed by @SuppressWarnings(\"shamash:all\").", out.isEmpty())
    }
}
