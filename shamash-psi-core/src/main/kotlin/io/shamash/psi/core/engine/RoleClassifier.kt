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
package io.shamash.psi.core.engine

import io.shamash.psi.core.config.schema.v1.model.Matcher
import io.shamash.psi.core.config.schema.v1.model.Role
import io.shamash.psi.core.facts.model.v1.ClassFact

data class RoleClassification(
    val roleToClasses: Map<String, Set<String>>,
    val classToRole: Map<String, String>,
)

/**
 * Classifies classes into roles using:
 * - role priority (higher wins)
 * - tie-breaker: role name
 *
 * Matchers are compiled once per role.
 */
class RoleClassifier(
    private val multiRole: Boolean = false,
) {
    fun classify(
        classes: List<ClassFact>,
        roles: Map<String, Role>,
    ): RoleClassification {
        // Compile matchers once per role
        val compiledRoles: List<CompiledRole> =
            roles.entries
                .sortedWith(
                    compareByDescending<Map.Entry<String, Role>> { it.value.priority }
                        .thenBy { it.key },
                ).map { (name, def) ->
                    CompiledRole(
                        name = name,
                        priority = def.priority,
                        matcher = MatcherCompiler.compile(def.match),
                    )
                }

        val roleToClasses = LinkedHashMap<String, MutableSet<String>>()
        val classToRole = LinkedHashMap<String, String>()

        compiledRoles.forEach { roleToClasses.putIfAbsent(it.name, linkedSetOf()) }

        for (c in classes) {
            val matched = mutableListOf<String>()

            for (role in compiledRoles) {
                if (role.matcher.matches(c)) {
                    matched += role.name
                    if (!multiRole) break
                }
            }

            if (matched.isEmpty()) continue

            if (multiRole) {
                matched.forEach { r -> roleToClasses.getOrPut(r) { linkedSetOf() }.add(c.fqName) }
                classToRole[c.fqName] = matched.first()
            } else {
                val winner = matched.first()
                roleToClasses.getOrPut(winner) { linkedSetOf() }.add(c.fqName)
                classToRole[c.fqName] = winner
            }
        }

        return RoleClassification(
            roleToClasses = roleToClasses.mapValues { it.value.toSet() },
            classToRole = classToRole.toMap(),
        )
    }

    private data class CompiledRole(
        val name: String,
        val priority: Int,
        val matcher: CompiledMatcher,
    )
}

/**
 * Compiled matcher tree – all regex are compiled once here.
 */
private object MatcherCompiler {
    fun compile(m: Matcher): CompiledMatcher =
        when (m) {
            is Matcher.AnyOf -> CompiledMatcher.AnyOf(m.anyOf.map { compile(it) })
            is Matcher.AllOf -> CompiledMatcher.AllOf(m.allOf.map { compile(it) })
            is Matcher.Not -> CompiledMatcher.Not(compile(m.not))

            is Matcher.Annotation -> CompiledMatcher.Annotation(m.annotation)
            is Matcher.AnnotationPrefix -> CompiledMatcher.AnnotationPrefix(m.annotationPrefix)

            is Matcher.PackageRegex -> CompiledMatcher.PackageRegex(Regex(m.packageRegex))
            is Matcher.PackageContainsSegment -> CompiledMatcher.PackageContainsSegment(m.packageContainsSegment)

            is Matcher.ClassNameRegex -> CompiledMatcher.ClassNameRegex(Regex(m.classNameRegex))
            is Matcher.ClassNameEndsWith -> CompiledMatcher.ClassNameEndsWith(m.classNameEndsWith)
            is Matcher.ClassNameEndsWithAny -> CompiledMatcher.ClassNameEndsWithAny(m.classNameEndsWithAny)

            is Matcher.HasMainMethod -> CompiledMatcher.HasMainMethod(m.hasMainMethod)
            is Matcher.Implements -> CompiledMatcher.Implements(m.implements)
            is Matcher.Extends -> CompiledMatcher.Extends(m.extends)
        }
}

private sealed interface CompiledMatcher {
    fun matches(c: ClassFact): Boolean

    data class AnyOf(
        val anyOf: List<CompiledMatcher>,
    ) : CompiledMatcher {
        override fun matches(c: ClassFact) = anyOf.any { it.matches(c) }
    }

    data class AllOf(
        val allOf: List<CompiledMatcher>,
    ) : CompiledMatcher {
        override fun matches(c: ClassFact) = allOf.all { it.matches(c) }
    }

    data class Not(
        val not: CompiledMatcher,
    ) : CompiledMatcher {
        override fun matches(c: ClassFact) = !not.matches(c)
    }

    data class Annotation(
        val annotation: String,
    ) : CompiledMatcher {
        override fun matches(c: ClassFact) = annotation in c.annotationsFqns
    }

    data class AnnotationPrefix(
        val prefix: String,
    ) : CompiledMatcher {
        override fun matches(c: ClassFact) = c.annotationsFqns.any { it.startsWith(prefix) }
    }

    data class PackageRegex(
        val regex: Regex,
    ) : CompiledMatcher {
        override fun matches(c: ClassFact) = regex.containsMatchIn(c.packageName)
    }

    data class PackageContainsSegment(
        val seg: String,
    ) : CompiledMatcher {
        override fun matches(c: ClassFact) = c.packageName.split('.').any { it == seg }
    }

    data class ClassNameRegex(
        val regex: Regex,
    ) : CompiledMatcher {
        override fun matches(c: ClassFact) = regex.containsMatchIn(c.simpleName)
    }

    data class ClassNameEndsWith(
        val suf: String,
    ) : CompiledMatcher {
        override fun matches(c: ClassFact) = c.simpleName.endsWith(suf)
    }

    data class ClassNameEndsWithAny(
        val sufs: List<String>,
    ) : CompiledMatcher {
        override fun matches(c: ClassFact) = sufs.any { c.simpleName.endsWith(it) }
    }

    data class HasMainMethod(
        val flag: Boolean,
    ) : CompiledMatcher {
        override fun matches(c: ClassFact) = c.hasMainMethod == flag
    }

    data class Implements(
        val iface: String,
    ) : CompiledMatcher {
        override fun matches(c: ClassFact) = iface in c.interfacesFqns
    }

    data class Extends(
        val superFqn: String,
    ) : CompiledMatcher {
        override fun matches(c: ClassFact) = c.superClassFqn == superFqn
    }
}
