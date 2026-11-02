/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.asm.core.rules

import io.shamash.artifacts.contract.FindingSeverity
import io.shamash.asm.core.config.ConfigValidation
import io.shamash.asm.core.config.SchemaResources
import io.shamash.asm.core.config.schema.v1.model.RuleDef
import io.shamash.asm.core.config.schema.v1.model.RuleScope
import io.shamash.asm.core.engine.rules.api.ForbiddenAnnotationUsageRule
import io.shamash.asm.core.facts.model.ClassFact
import io.shamash.asm.core.facts.model.FieldRef
import io.shamash.asm.core.facts.model.MethodRef
import io.shamash.asm.core.facts.model.OriginKind
import io.shamash.asm.core.facts.model.SourceLocation
import io.shamash.asm.core.facts.model.TypeRef
import io.shamash.asm.core.facts.query.FactIndex
import org.objectweb.asm.Opcodes
import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AnnotationOwnerScopeTest {
    @Test
    fun `class method and field annotations honor the same source owner scope`() {
        val facts = facts()
        val config =
            SchemaResources.openReferenceYaml().reader().use {
                assertNotNull(ConfigValidation.loadAndValidateV1(StringReader(it.readText())).config)
            }
        val evaluator = ForbiddenAnnotationUsageRule()
        val definition =
            RuleDef(
                "api",
                "forbiddenAnnotationUsage",
                null,
                true,
                FindingSeverity.ERROR,
                null,
                mapOf("forbid" to listOf("flag.Forbidden")),
            )
        assertEquals(9, evaluator.evaluate(facts, definition, config).size)

        val scope =
            RuleScope(
                includeRoles = listOf("core"),
                excludeRoles = null,
                includePackages = listOf("^a$"),
                excludePackages = null,
                includeGlobs = listOf("classes/**"),
                excludeGlobs = null,
            )
        val restricted = definition.copy(scope = scope)
        val matching = evaluator.evaluate(facts, restricted, config)
        assertEquals(3, matching.size)
        assertEquals(setOf("class", "method", "field"), matching.mapNotNull { it.data["targetKind"] }.toSet())
        assertTrue(matching.all { it.classFqn == "a.Visible" })

        val shuffled =
            facts.copy(
                classes = facts.classes.reversed(),
                methods = facts.methods.reversed(),
                fields = facts.fields.reversed(),
            )
        assertEquals(matching, evaluator.evaluate(shuffled, restricted, config))

        val excludePackage = restricted.copy(scope = scope.copy(excludePackages = listOf("^a$")))
        assertTrue(evaluator.evaluate(facts, excludePackage, config).isEmpty())

        val excludePath = restricted.copy(scope = scope.copy(excludeGlobs = listOf("classes/a/Visible.class")))
        assertTrue(evaluator.evaluate(facts, excludePath, config).isEmpty())
        assertTrue(evaluator.evaluate(facts, restricted.copy(roles = listOf("other")), config).isEmpty())
        assertTrue(
            evaluator.evaluate(facts, restricted.copy(scope = scope.copy(excludeRoles = listOf("core"))), config).isEmpty(),
        )
        assertTrue(evaluator.evaluate(facts.copy(classToRole = emptyMap()), restricted, config).isEmpty())
    }

    private fun facts(): FactIndex {
        val classes =
            listOf(
                newClass("a.Visible"),
                newClass("a.Ignored"),
                newClass("b.Excluded"),
            )
        val methods =
            classes.map { c ->
                MethodRef(
                    owner = c.type,
                    name = "run",
                    desc = "()V",
                    signature = null,
                    access = Opcodes.ACC_PUBLIC,
                    isConstructor = false,
                    returnType = null,
                    parameterTypes = emptyList(),
                    throwsTypes = emptyList(),
                    annotationsFqns = setOf("flag.Forbidden"),
                    location = c.location,
                )
            }
        val fields =
            classes.map { c ->
                FieldRef(
                    owner = c.type,
                    name = "field",
                    desc = "I",
                    signature = null,
                    access = Opcodes.ACC_PUBLIC,
                    fieldType = null,
                    annotationsFqns = setOf("flag.Forbidden"),
                    location = c.location,
                )
            }
        return FactIndex(
            classes = classes,
            methods = methods,
            fields = fields,
            edges = emptyList(),
            roles = mapOf("core" to setOf("a.Visible", "b.Excluded"), "other" to setOf("a.Ignored")),
            classToRole = mapOf("a.Visible" to "core", "a.Ignored" to "other", "b.Excluded" to "core"),
        )
    }

    private fun newClass(name: String): ClassFact =
        ClassFact(
            type = TypeRef.fromInternalName(name.replace('.', '/')),
            access = Opcodes.ACC_PUBLIC,
            superType = null,
            interfaces = emptySet(),
            annotationsFqns = setOf("flag.Forbidden"),
            hasMainMethod = false,
            location = SourceLocation(OriginKind.DIR_CLASS, "classes/" + name.replace('.', '/') + ".class"),
        )
}
