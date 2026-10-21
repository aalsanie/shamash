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
package io.shamash.psi.core.facts

import com.intellij.testFramework.builders.EmptyModuleFixtureBuilder
import com.intellij.testFramework.fixtures.CodeInsightFixtureTestCase
import io.shamash.psi.core.facts.model.v1.DependencyKind
import kotlin.test.assertTrue

class KotlinFactOwnerTest : CodeInsightFixtureTestCase<EmptyModuleFixtureBuilder<*>>() {
    fun testKotlinPropertiesMethodsAndNestedClassesRetainTheirOwners() {
        val file =
            myFixture.configureByText(
                "Sample.kt",
                """
                package example

                class Parent {
                    val label: String = "label"

                    fun echo(input: String): String = input

                    class Nested {
                        fun nested(input: String): String = input
                    }
                }
                """.trimIndent(),
            )

        val result = FactExtractor.extractResult(file)
        val facts = result.facts

        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertTrue(facts.classes.any { it.fqName == "example.Parent" })
        assertTrue(facts.classes.any { it.fqName == "example.Parent.Nested" })
        assertTrue(facts.fields.any { it.name == "label" && it.containingClassFqn == "example.Parent" })
        assertTrue(facts.methods.any { it.name == "echo" && it.containingClassFqn == "example.Parent" })
        assertTrue(facts.methods.any { it.name == "nested" && it.containingClassFqn == "example.Parent.Nested" })
        assertTrue(
            facts.dependencies.any {
                it.fromClassFqn == "example.Parent" && it.kind == DependencyKind.PARAMETER_TYPE
            },
        )
    }

    fun testAnonymousObjectMembersAreNotAttributedToOuterClass() {
        val file =
            myFixture.configureByText(
                "Anonymous.kt",
                """
                package example

                class Parent {
                    val task = object : Runnable {
                        override fun run() {}
                    }
                }
                """.trimIndent(),
            )

        val result = FactExtractor.extractResult(file)

        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertTrue(
            result.facts.methods.none { it.name == "run" && it.containingClassFqn == "example.Parent" },
        )
    }

    fun testJavaMembersKeepTheirOriginalContainingClass() {
        val file =
            myFixture.configureByText(
                "Sample.java",
                """
                package example;

                class Parent {
                    String label;
                    String echo(String input) { return input; }
                }
                """.trimIndent(),
            )

        val result = FactExtractor.extractResult(file)
        val facts = result.facts

        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertTrue(facts.fields.any { it.name == "label" && it.containingClassFqn == "example.Parent" })
        assertTrue(facts.methods.any { it.name == "echo" && it.containingClassFqn == "example.Parent" })
    }
}
