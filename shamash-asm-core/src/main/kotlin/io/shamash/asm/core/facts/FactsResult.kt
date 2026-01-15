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
package io.shamash.asm.core.facts

import io.shamash.asm.core.facts.query.FactIndex

/**
 * Facts output:
 * - facts: best-effort extracted facts (never null)
 * - errors: structured extraction issues (never throws for user code)
 */
data class FactsResult(
    val facts: FactIndex,
    val errors: List<FactsError>,
) {
    val hasErrors: Boolean get() = errors.isNotEmpty()
}

/**
 * Structured extraction error without leaking huge stack traces into model.
 */
data class FactsError(
    val originId: String,
    val phase: String, // e.g. "class:visit", "method:visitInsn", "computeFacts"
    val message: String,
    val throwableClass: String? = null,
)
