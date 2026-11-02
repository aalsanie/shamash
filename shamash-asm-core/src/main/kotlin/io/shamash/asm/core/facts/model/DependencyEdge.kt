/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.asm.core.facts.model

data class DependencyEdge(
    val from: TypeRef,
    val to: TypeRef,
    val kind: DependencyKind,
    val location: SourceLocation,
    val detail: String? = null,
)

enum class DependencyKind {
    METHOD_CALL,
    FIELD_ACCESS,

    FIELD_TYPE,
    METHOD_PARAM_TYPE,
    METHOD_RETURN_TYPE,
    THROWS_TYPE,

    EXTENDS,
    IMPLEMENTS,

    ANNOTATION_TYPE,

    TYPE_INSTRUCTION,
    CONST_TYPE,
    GENERIC_TYPE,
}
