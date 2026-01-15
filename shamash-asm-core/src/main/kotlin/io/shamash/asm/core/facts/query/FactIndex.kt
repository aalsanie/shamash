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
package io.shamash.asm.core.facts.query

import io.shamash.asm.core.facts.model.ClassFact
import io.shamash.asm.core.facts.model.DependencyEdge
import io.shamash.asm.core.facts.model.FieldRef
import io.shamash.asm.core.facts.model.MethodRef

/**
 * Aggregated facts (bytecode-based).
 *
 * Note: role assignment is not performed by facts extraction. These maps are populated by the engine.
 */
data class FactIndex(
    val classes: List<ClassFact>,
    val methods: List<MethodRef>,
    val fields: List<FieldRef>,
    val edges: List<DependencyEdge>,
    val roles: Map<String, Set<String>>,
    val classToRole: Map<String, String>,
) {
    companion object {
        fun empty(): FactIndex =
            FactIndex(
                classes = emptyList(),
                methods = emptyList(),
                fields = emptyList(),
                edges = emptyList(),
                roles = emptyMap(),
                classToRole = emptyMap(),
            )
    }

    fun merge(other: FactIndex): FactIndex =
        copy(
            classes = this.classes + other.classes,
            methods = this.methods + other.methods,
            fields = this.fields + other.fields,
            edges = this.edges + other.edges,
            // roles/classToRole are engine-owned; last writer wins if caller merges with populated maps.
            roles = if (other.roles.isNotEmpty()) other.roles else this.roles,
            classToRole = if (other.classToRole.isNotEmpty()) other.classToRole else this.classToRole,
        )
}
