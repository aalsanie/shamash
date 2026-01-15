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
package io.shamash.asm.core.engine.roles

import io.shamash.asm.core.config.schema.v1.model.RoleDef
import io.shamash.asm.core.config.schema.v1.model.RoleId
import io.shamash.asm.core.facts.model.ClassFact
import io.shamash.asm.core.facts.query.FactIndex

/**
 * RoleClassifier
 *
 * Used by engine to classify each class into a single role:
 * - Highest priority role among matches
 * - Tie-break by roleId lexicographic order
 *
 * Output:
 * - classToRole: classFqn -> roleId
 * - roles: roleId -> Set<classFqn>
 */
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
            }
            // Deterministic ordering: highest priority first, then roleId asc
            .sortedWith(
                compareByDescending<CompiledRole> { it.priority }
                    .thenBy { it.id },
            )

    fun classify(classes: List<ClassFact>): RoleMatchResult {
        if (compiled.isEmpty() || classes.isEmpty()) {
            return RoleMatchResult(classToRole = emptyMap(), roles = emptyMap())
        }

        // Deterministic iteration: sort by fqName
        val sortedClasses = classes.sortedBy { it.fqName }

        val classToRole = LinkedHashMap<String, String>(sortedClasses.size)
        val rolesToClasses = LinkedHashMap<String, LinkedHashSet<String>>()

        for (c in sortedClasses) {
            val winner = pickRole(c) ?: continue
            classToRole[c.fqName] = winner.id
            rolesToClasses.getOrPut(winner.id) { LinkedHashSet() }.add(c.fqName)
        }

        // Freeze sets as immutable, preserving insertion order
        val frozenRoles: Map<String, Set<String>> =
            rolesToClasses.mapValues { (_, v) -> v.toSet() }

        return RoleMatchResult(
            classToRole = classToRole.toMap(),
            roles = frozenRoles,
        )
    }

    /**
     * Convenience: apply classification into a new FactIndex (engine writes these fields).
     */
    fun applyToFacts(facts: FactIndex): FactIndex {
        val r = classify(facts.classes)
        return facts.copy(
            roles = r.roles,
            classToRole = r.classToRole,
        )
    }

    private fun pickRole(c: ClassFact): CompiledRole? {
        // compiled is already sorted by priority desc then id asc.
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

/**
 * Engine-visible role classification output.
 */
internal data class RoleMatchResult(
    val classToRole: Map<String, String>, // classFqn -> roleId
    val roles: Map<String, Set<String>>, // roleId -> set of classFqn
)
