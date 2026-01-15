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

import io.shamash.asm.core.config.schema.v1.model.RuleDef

/**
 * Registry of shipped ASM rules.
 *
 * Lookup semantics:
 * - Rule implementations are registered by [Rule.id] (canonical base id).
 * - Engine resolves a rule implementation from config [RuleDef] via:
 *     "${ruleDef.type}.${ruleDef.name}"
 *
 * Notes:
 * - Role-scoped variants are handled by the engine (execution-time), not by the registry.
 * - Registry validates uniqueness and provides stable iteration order.
 */
interface RuleRegistry {
    /** Deterministic iteration order (sorted by id). */
    fun all(): List<Rule>

    /** Lookup by canonical base id (e.g., "arch.forbiddenPackages"). */
    fun byId(ruleId: String): Rule?

    /** Resolve from config rule definition (type + name). */
    fun resolve(rule: RuleDef): Rule? = byId("${rule.type}.${rule.name}")
}
