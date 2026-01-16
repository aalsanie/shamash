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
import io.shamash.asm.core.engine.roles.MatcherEvaluator
import io.shamash.asm.core.facts.model.ClassFact
import io.shamash.asm.core.facts.model.OriginKind
import io.shamash.asm.core.facts.model.SourceLocation
import io.shamash.asm.core.facts.model.TypeRef
import org.objectweb.asm.Opcodes
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MatcherEvaluatorTest {
    private fun classFact(
        fqn: String,
        annotations: Set<String> = emptySet(),
    ): ClassFact {
        val type = TypeRef.fromInternalName(fqn)
        return ClassFact(
            type = type,
            access = Opcodes.ACC_PUBLIC,
            superType = TypeRef.fromInternalName("java.lang.Object"),
            interfaces = emptySet(),
            annotationsFqns = annotations,
            hasMainMethod = false,
            location = SourceLocation(OriginKind.DIR_CLASS, originPath = "${'$'}fqn.class"),
        )
    }

    @Test
    fun `package contains segment matches exact segments only`() {
        val c1 = classFact("com.acme.service.UserService")
        val c2 = classFact("com.acmeservice.User")

        val m = Matcher.PackageContainsSegment("service")
        val compiled = MatcherEvaluator.compile(m)

        assertTrue(compiled.matches(c1))
        assertFalse(compiled.matches(c2))
    }

    @Test
    fun `annotation and annotationPrefix match correctly`() {
        val c =
            classFact(
                "com.acme.web.HomeController",
                annotations = setOf("org.springframework.stereotype.Controller", "com.acme.MyAnno"),
            )

        val exact = Matcher.Annotation("org.springframework.stereotype.Controller")
        val prefix = Matcher.AnnotationPrefix("org.springframework")
        val missing = Matcher.Annotation("org.springframework.web.bind.annotation.RestController")

        assertTrue(MatcherEvaluator.compile(exact).matches(c))
        assertTrue(MatcherEvaluator.compile(prefix).matches(c))
        assertFalse(MatcherEvaluator.compile(missing).matches(c))
    }

    @Test
    fun `anyOf, allOf and not compose deterministically`() {
        val c = classFact("com.acme.controller.UserController")

        val any =
            Matcher.AnyOf(
                anyOf =
                    listOf(
                        Matcher.ClassNameEndsWith("Service"),
                        Matcher.ClassNameEndsWith("Controller"),
                    ),
            )

        val all =
            Matcher.AllOf(
                allOf =
                    listOf(
                        Matcher.PackageRegex("^com\\.acme\\..*$"),
                        Matcher.ClassNameEndsWith("Controller"),
                    ),
            )

        val not = Matcher.Not(Matcher.ClassNameEndsWith("Service"))

        assertTrue(MatcherEvaluator.compile(any).matches(c))
        assertTrue(MatcherEvaluator.compile(all).matches(c))
        assertTrue(MatcherEvaluator.compile(not).matches(c))
    }
}
