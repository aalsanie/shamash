/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.psi.core.facts.model.v1

import com.intellij.openapi.util.TextRange

data class ClassFact(
    val fqName: String,
    val packageName: String,
    val simpleName: String,
    val annotationsFqns: Set<String>,
    val superClassFqn: String?,
    val interfacesFqns: Set<String>,
    val hasMainMethod: Boolean,
    val filePath: String,
    val textRange: TextRange? = null,
)
