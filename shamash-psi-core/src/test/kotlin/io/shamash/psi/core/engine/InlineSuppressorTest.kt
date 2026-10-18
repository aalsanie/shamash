/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
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
package io.shamash.psi.core.engine

import com.intellij.testFramework.builders.EmptyModuleFixtureBuilder
import com.intellij.testFramework.fixtures.CodeInsightFixtureTestCase
import io.shamash.artifacts.contract.Finding
import io.shamash.artifacts.contract.FindingSeverity
import kotlin.test.assertEquals

class InlineSuppressorTest : CodeInsightFixtureTestCase<EmptyModuleFixtureBuilder<*>>() {
    fun testJavaStringLiteralIsNotCommentSuppression() {
        assertKept(
            "Sample.java",
            """
            class Sample {
                String text = "// shamash:ignore all";
            }
            """.trimIndent(),
        )
    }

    fun testKotlinRawStringLiteralIsNotFileWideSuppression() {
        assertKept(
            "Sample.kt",
            "val text = \"\"\"shamash:ignore all\"\"\"\nclass Sample",
        )
    }

    fun testKotlinStringContainingAnnotationIsNotSuppression() {
        assertKept(
            "Sample.kt",
            "val text = \"@Suppress(\\\"shamash:all\\\")\"\nclass Sample",
        )
    }

    fun testCommentLookingTextInMultilineKotlinStringIsIgnored() {
        assertKept(
            "Sample.kt",
            """
            val text = \"\"\"
            // shamash:ignore all
            \"\"\"
            class Sample
            """.trimIndent(),
        )
    }

    fun testKotlinSuppressAnnotationSupportsMultipleArguments() {
        assertSuppressed(
            "Sample.kt",
            "@Suppress(\"unused\", \"shamash:arch.rule\")\\nclass Sample",
        )
    }

    fun testActualLineCommentSuppressesFile() {
        assertSuppressed(
            "Sample.kt",
            "// shamash:ignore arch.rule\nclass Sample",
        )
    }

    fun testActualBlockCommentSuppressesFile() {
        assertSuppressed(
            "Sample.java",
            "/* shamash:ignore arch.rule */\nclass Sample {}",
        )
    }

    fun testInlineCommentSuppressesNearbyDeclaration() {
        assertSuppressed(
            "Sample.kt",
            "val ignored = 0 // shamash:ignore arch.rule\nclass Sample",
        )
    }

    fun testActualKotlinSuppressAnnotationStillWorks() {
        assertSuppressed(
            "Sample.kt",
            "@Suppress(\"shamash:arch.rule\")\nclass Sample",
        )
    }

    fun testOtherRulesAreNotSuppressedByActualComment() {
        assertKept(
            "Sample.kt",
            "// shamash:ignore arch.different\nclass Sample",
        )
    }

    private fun assertKept(
        name: String,
        source: String,
    ) = assertCount(name, source, 1)

    private fun assertSuppressed(
        name: String,
        source: String,
    ) = assertCount(name, source, 0)

    private fun assertCount(
        name: String,
        source: String,
        expected: Int,
    ) {
        val file = myFixture.configureByText(name, source)
        val finding =
            Finding(
                ruleId = "arch.rule",
                message = "test",
                filePath = name,
                severity = FindingSeverity.ERROR,
                classFqn = "Sample",
                startOffset = source.indexOf("class Sample"),
            )
        assertEquals(expected, InlineSuppressor.apply(listOf(finding), file).size)
    }
}
