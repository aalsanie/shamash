/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.psi.core.facts.model.v1

import com.intellij.openapi.util.TextRange

data class DependencyFact(
    val fromClassFqn: String,
    val toTypeFqn: String,
    val kind: DependencyKind,
    val filePath: String,
    val textRange: TextRange? = null,
    val detail: String? = null,
)

enum class DependencyKind {
    METHOD_CALL,
    FIELD_TYPE,
    PARAMETER_TYPE,
    RETURN_TYPE,
    EXTENDS,
    IMPLEMENTS,
    ANNOTATION_TYPE,
}
