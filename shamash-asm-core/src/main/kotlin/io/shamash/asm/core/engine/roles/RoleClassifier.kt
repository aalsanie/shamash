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
package io.shamash.asm.core.engine.roles

import io.shamash.asm.core.config.schema.v1.model.RoleDef
import io.shamash.asm.core.config.schema.v1.model.RoleId
import io.shamash.asm.core.facts.model.ClassFact
import io.shamash.asm.core.facts.query.FactIndex

/** Each class gets one role: highest priority wins, then lexicographically smallest id. */
internal class RoleClassifier(
    roles: Map<RoleId, RoleDef>,
) {
    private val compiled: List<CompiledRole> =
        roles.entries
            .map { (roleId, def) ->
                CompiledRole(
                    id = roleId,
                    priority = def.priority,
                    matcher = MatcherEvaluator.compile(def.match),
                )
            }.sortedWith(
                compareByDescending<CompiledRole> { it.priority }
                    .thenBy { it.id },
            )

    fun classify(classes: List<ClassFact>): RoleMatchResult {
        if (compiled.isEmpty() || classes.isEmpty()) {
            return RoleMatchResult(classToRole = emptyMap(), roles = emptyMap())
        }

        val sortedClasses = classes.sortedBy { it.fqName }

        val classToRole = LinkedHashMap<String, String>(sortedClasses.size)
        val rolesToClasses = LinkedHashMap<String, LinkedHashSet<String>>()

        for (c in sortedClasses) {
            val winner = pickRole(c) ?: continue
            classToRole[c.fqName] = winner.id
            rolesToClasses.getOrPut(winner.id) { LinkedHashSet() }.add(c.fqName)
        }

        val frozenRoles: Map<String, Set<String>> =
            rolesToClasses.mapValues { (_, v) -> v.toSet() }

        return RoleMatchResult(
            classToRole = classToRole.toMap(),
            roles = frozenRoles,
        )
    }

    fun applyToFacts(facts: FactIndex): FactIndex {
        val r = classify(facts.classes)
        return facts.copy(
            roles = r.roles,
            classToRole = r.classToRole,
        )
    }

    private fun pickRole(c: ClassFact): CompiledRole? {
        for (role in compiled) {
            if (role.matcher.matches(c)) return role
        }
        return null
    }

    private data class CompiledRole(
        val id: String,
        val priority: Int,
        val matcher: MatcherEvaluator.CompiledMatcher,
    )
}

internal data class RoleMatchResult(
    val classToRole: Map<String, String>,
    val roles: Map<String, Set<String>>,
)
