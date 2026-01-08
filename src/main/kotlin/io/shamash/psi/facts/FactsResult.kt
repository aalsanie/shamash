/*
 * Copyright © 2025-2026 | Shamash is a refactoring tool that enforces clean architecture.
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
package io.shamash.psi.facts

import io.shamash.psi.facts.model.v1.FactsIndex

/**
 * Production-friendly facts output:
 * - facts: best-effort extracted facts (never null)
 * - errors: structured extraction issues (never throws unless canceled)
 *
 * Deterministic:
 * - callers can compare FactsResult across runs for the same file.
 */
data class FactsResult(
    val facts: FactsIndex,
    val errors: List<FactsError>,
) {
    val hasErrors: Boolean get() = errors.isNotEmpty()
}

/**
 * Structured extraction error without leaking huge stack traces into model.
 */
data class FactsError(
    val fileId: String,
    val phase: String, // e.g. "uast:visitCallExpression", "psi:visitMethod", "computeFacts"
    val message: String,
    val throwableClass: String? = null,
)
