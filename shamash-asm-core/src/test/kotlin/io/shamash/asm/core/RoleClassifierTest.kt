/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.asm.core

import io.shamash.asm.core.config.schema.v1.model.Matcher
import io.shamash.asm.core.config.schema.v1.model.RoleDef
import io.shamash.asm.core.engine.roles.RoleClassifier
import io.shamash.asm.core.facts.model.ClassFact
import io.shamash.asm.core.facts.model.OriginKind
import io.shamash.asm.core.facts.model.SourceLocation
import io.shamash.asm.core.facts.model.TypeRef
import org.objectweb.asm.Opcodes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoleClassifierTest {
    private fun classFact(fqn: String): ClassFact {
        val type = TypeRef.fromInternalName(fqn)
        return ClassFact(
            type = type,
            access = Opcodes.ACC_PUBLIC,
            superType = TypeRef.fromInternalName("java.lang.Object"),
            interfaces = emptySet(),
            annotationsFqns = emptySet(),
            hasMainMethod = false,
            location = SourceLocation(OriginKind.DIR_CLASS, originPath = "${'$'}fqn.class"),
        )
    }

    @Test
    fun `highest priority role wins, tie breaks by role id`() {
        val roles =
            mapOf(
                // tie on priority; lexicographically smaller id should win
                "alpha" to RoleDef(priority = 50, description = "", match = Matcher.ClassNameEndsWith("Service")),
                "beta" to RoleDef(priority = 50, description = "", match = Matcher.ClassNameEndsWith("Service")),
                // higher priority should win over alpha/beta when both match
                "top" to RoleDef(priority = 90, description = "", match = Matcher.PackageContainsSegment("service")),
            )

        val classifier = RoleClassifier(roles)
        val classes =
            listOf(
                classFact("com.acme.service.UserService"),
                classFact("com.acme.other.Other"),
            )

        val result = classifier.classify(classes)

        assertEquals("top", result.classToRole["com.acme.service.UserService"])

        assertTrue("com.acme.other.Other" !in result.classToRole)
    }

    @Test
    fun `deterministic ordering of role class sets`() {
        val roles =
            mapOf(
                "svc" to RoleDef(priority = 10, description = "", match = Matcher.ClassNameEndsWith("Service")),
            )

        val classifier = RoleClassifier(roles)
        val classes =
            listOf(
                classFact("com.acme.ZService"),
                classFact("com.acme.AService"),
            )

        val r = classifier.classify(classes)
        assertEquals(listOf("com.acme.AService", "com.acme.ZService"), r.roles.getValue("svc").toList())
    }
}
