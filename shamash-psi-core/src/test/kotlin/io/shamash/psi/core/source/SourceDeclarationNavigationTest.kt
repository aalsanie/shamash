/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.psi.core.source

import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.builders.EmptyModuleFixtureBuilder
import com.intellij.testFramework.fixtures.CodeInsightFixtureTestCase
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtTypeAlias
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SourceDeclarationNavigationTest : CodeInsightFixtureTestCase<EmptyModuleFixtureBuilder<*>>() {
    fun testKotlinNameIdentifiersRecoverTheirOwnDeclarations() {
        val source =
            myFixture.configureByText(
                "Declarations.kt",
                """
                package example
                typealias Alias = Target
                class Target(val constructorParameter: Target?) {
                    val property: Alias? = null
                    fun read(parameter: Alias): Alias = parameter
                }
                """.trimIndent(),
            )
        val declarations = PsiTreeUtil.findChildrenOfType(source, KtNamedDeclaration::class.java)
        val kinds =
            listOf(
                KtClassOrObject::class.java,
                KtNamedFunction::class.java,
                KtParameter::class.java,
                KtProperty::class.java,
                KtTypeAlias::class.java,
            )
        assertTrue(kinds.all { type -> declarations.any(type::isInstance) }, declarations.toString())

        val extractor = SourceDependencyExtractor { null }
        for (declaration in declarations) {
            val nameToken = declaration.nameIdentifier ?: continue
            assertSame(
                declaration,
                extractor.declarationOf(nameToken),
                "A Kotlin name token must navigate to its own declaration: ${declaration.name}",
            )
        }
    }
}
