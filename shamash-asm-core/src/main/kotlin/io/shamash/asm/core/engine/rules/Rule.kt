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
package io.shamash.asm.core.engine.rules

import io.shamash.artifacts.contract.Finding
import io.shamash.asm.core.config.schema.v1.model.RuleDef
import io.shamash.asm.core.config.schema.v1.model.ShamashAsmConfigV1
import io.shamash.asm.core.facts.query.FactIndex

/**
 * Engine rule contract.
 *
 * Responsibilities:
 * - A Rule evaluates *facts* using its own params (embedded in [RuleDef.params]).
 * - A Rule returns findings; it does NOT apply exceptions or baselines (engine does).
 * - A Rule must be deterministic: stable ordering and stable content for identical inputs.
 *
 * Failure semantics:
 * - Rules should prefer being defensive with params and return empty findings on malformed params
 * - Engine decides how to surface rule errors (e.g., EngineError).
 */
interface Rule {
    /**
     * Canonical rule identifier, e.g. "api.forbiddenAnnotationUsage".
     *
     * The engine may decorate rule ids at runtime (e.g. role-scoped instances),
     * but [id] must remain the canonical base id for registry/wiring.
     */
    val id: String

    /**
     * Evaluate a single rule definition against the extracted facts.
     *
     * @param facts Extracted bytecode facts (classes, methods, fields, dependency edges, etc.)
     * @param rule  The config rule definition (type, name, severity, params, scope)
     * @param config Full config (needed for global settings like roles, analysis toggles, etc.)
     */
    fun evaluate(
        facts: FactIndex,
        rule: RuleDef,
        config: ShamashAsmConfigV1,
    ): List<Finding>
}
