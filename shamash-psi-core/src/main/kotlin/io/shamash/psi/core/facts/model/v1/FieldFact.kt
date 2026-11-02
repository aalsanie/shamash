/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.psi.core.facts.model.v1

import com.intellij.openapi.util.TextRange

data class FieldFact(
    val containingClassFqn: String,
    val name: String,
    val typeFqn: String,
    val visibility: Visibility,
    val isStatic: Boolean,
    val isFinal: Boolean,
    val annotationsFqns: Set<String>,
    val filePath: String,
    val textRange: TextRange? = null,
) {
    val fqName: String = "$containingClassFqn#$name"
}
