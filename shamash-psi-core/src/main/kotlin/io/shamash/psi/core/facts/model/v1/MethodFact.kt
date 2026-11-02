/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.psi.core.facts.model.v1

import com.intellij.openapi.util.TextRange

data class MethodFact(
    val containingClassFqn: String,
    val name: String,
    val signature: String,
    val visibility: Visibility,
    val isStatic: Boolean,
    val isAbstract: Boolean,
    val isConstructor: Boolean,
    val returnTypeFqn: String?,
    val parameterTypeFqns: List<String>,
    val annotationsFqns: Set<String>,
    val filePath: String,
    val textRange: TextRange? = null,
) {
    val fqName: String = "$containingClassFqn#$signature"
}

enum class Visibility { PUBLIC, PROTECTED, PACKAGE_PRIVATE, PRIVATE }
