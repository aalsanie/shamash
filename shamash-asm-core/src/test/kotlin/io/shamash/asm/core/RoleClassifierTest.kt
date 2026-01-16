/*
 * Copyright © 2025-2026 | Shamash
 *
 * Shamash is a JVM architecture enforcement tool that helps teams
 * define, validate, and continuously enforce architectural boundaries.
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

        // UserService matches both 'top' and ('alpha'/'beta'), highest priority wins.
        assertEquals("top", result.classToRole["com.acme.service.UserService"])

        // Other does not match any role.
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
