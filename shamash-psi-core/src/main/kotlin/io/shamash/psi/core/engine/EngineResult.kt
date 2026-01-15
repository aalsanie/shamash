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
package io.shamash.psi.core.engine

import io.shamash.artifacts.contract.Finding

/**
 * Production-friendly engine output:
 * - findings: best-effort findings (never null)
 * - errors: structured execution issues (never throws unless canceled)
 *
 * Deterministic:
 * - callers can compare EngineResult across runs for the same file.
 */
data class EngineResult(
    val findings: List<Finding>,
    val errors: List<EngineError>,
) {
    val hasErrors: Boolean get() = errors.isNotEmpty()
}

/**
 * Structured engine error without leaking huge stack traces into the model.
 */
data class EngineError(
    val fileId: String,
    val phase: String, // e.g. "facts:toUElementOfType", "roleIndex:getOrBuild", "rule:params", "rule:crash", "suppress:exceptions"
    val message: String,
    val throwableClass: String? = null,
    val ruleId: String? = null,
)
