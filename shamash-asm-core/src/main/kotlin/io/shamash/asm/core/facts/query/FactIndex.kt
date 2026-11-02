/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.asm.core.facts.query

import io.shamash.asm.core.facts.model.ClassFact
import io.shamash.asm.core.facts.model.DependencyEdge
import io.shamash.asm.core.facts.model.FieldRef
import io.shamash.asm.core.facts.model.MethodRef

/** Role maps are populated by the engine, not by facts extraction. */
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
