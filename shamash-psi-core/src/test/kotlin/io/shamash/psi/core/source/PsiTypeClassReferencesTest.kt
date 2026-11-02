/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.psi.core.source

import com.intellij.psi.PsiClass
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.builders.EmptyModuleFixtureBuilder
import com.intellij.testFramework.fixtures.CodeInsightFixtureTestCase
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PsiTypeClassReferencesTest : CodeInsightFixtureTestCase<EmptyModuleFixtureBuilder<*>>() {
    fun testNestedGenericsWildcardAndArrayPreserveInnerClass() {
        val file = myFixture.configureByText(
            "Types.java",
            """
            package demo;
            class Repository {}
            class Uses {
                java.util.Map<String, java.util.List<? extends Repository[]>> dependencies;
            }
            """.trimIndent(),
        )
        val cls = PsiTreeUtil.findChildrenOfType(file, PsiClass::class.java)
            .first { it.name == "Uses" }
        val type = cls.fields.single().type
        val result = PsiTypeClassReferences.collect(type)
        assertFalse(result.truncated)
        assertTrue(result.classes.any { it.qualifiedName == "demo.Repository" })
        assertTrue(result.classes.any { it.qualifiedName == "java.util.List" })
    }

    fun testSelfReferentialTypeBoundsTerminate() {
        val file = myFixture.configureByText(
            "Bounded.java",
            """
            package demo;
            class Repository {}
            class Bounded<T extends Comparable<T> & java.util.List<Repository>> {
                T field;
            }
            """.trimIndent(),
        )
        val cls = PsiTreeUtil.findChildrenOfType(file, PsiClass::class.java)
            .first { it.name == "Bounded" }
        val result = PsiTypeClassReferences.collect(cls.fields.single().type)
        assertFalse(result.truncated)
        assertTrue(result.classes.any { it.qualifiedName == "demo.Repository" })
    }

    fun testExceededRecursionDepthIsExplicitlyTruncated() {
        val file = myFixture.configureByText(
            "Deep.java",
            "package demo; class Deep { java.util.List<java.util.List<String>> nested; }",
        )
        val cls = PsiTreeUtil.findChildrenOfType(file, PsiClass::class.java)
            .first { it.name == "Deep" }
        val result = PsiTypeClassReferences.collect(cls.fields.single().type, maxDepth = 1)
        assertTrue(result.truncated)
    }
}
