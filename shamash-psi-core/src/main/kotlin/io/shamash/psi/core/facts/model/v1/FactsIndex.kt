/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.psi.core.facts.model.v1

data class FactsIndex(
    val classes: List<ClassFact>,
    val methods: List<MethodFact>,
    val fields: List<FieldFact>,
    val dependencies: List<DependencyFact>,
    val roles: Map<String, Set<String>>,
    val classToRole: Map<String, String>,
)
