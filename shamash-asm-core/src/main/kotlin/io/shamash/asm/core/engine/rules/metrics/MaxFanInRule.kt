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
package io.shamash.asm.core.engine.rules.metrics

import io.shamash.artifacts.contract.Finding
import io.shamash.artifacts.params.ParamError
import io.shamash.artifacts.params.Params
import io.shamash.asm.core.config.schema.v1.model.Granularity
import io.shamash.asm.core.config.schema.v1.model.RuleDef
import io.shamash.asm.core.config.schema.v1.model.ShamashAsmConfigV1
import io.shamash.asm.core.engine.rules.Rule
import io.shamash.asm.core.engine.rules.RuleUtil
import io.shamash.asm.core.facts.query.FactIndex

/**
 * metrics.maxFanIn
 *
 * Params:
 * - max: int (required, >= 0)
 * - granularity: "class" | "package" | "module" (optional, default "package")
 * - includeExternal: boolean (optional, default false)
 * - top: int (optional, default 10)  // number of violators to include as examples
 *
 * Semantics:
 * - Build dependency graph at requested granularity.
 * - fan-in(node) = number of distinct incoming edges.
 * - If any node fan-in > max -> emit a single finding listing top violators (deterministic, truncated).
 */
class MaxFanInRule : Rule {
    override val id: String = "metrics.maxFanIn"

    override fun evaluate(
        facts: FactIndex,
        rule: RuleDef,
        config: ShamashAsmConfigV1,
    ): List<Finding> {
        val params = readParams(rule) ?: return emptyList()

        val scope = RuleUtil.compileScope(rule.scope)

        val g =
            RuleUtil.buildDependencyGraph(
                facts = facts,
                granularity = params.granularity,
                includeExternalBuckets = params.includeExternal,
                scope = scope,
            )

        val fanIn = RuleUtil.fanIn(g)
        val violators =
            fanIn
                .filter { (_, v) -> v > params.max }
                .toList()
                .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })

        if (violators.isEmpty()) return emptyList()

        val top = violators.take(params.top)
        val examples = top.joinToString(",") { (n, v) -> "$n:$v" }

        // Anchor on first class in scope (stable)
        val anchor =
            facts.classes
                .asSequence()
                .sortedBy { it.fqName }
                .firstOrNull { c ->
                    val roleId = facts.classToRole[c.fqName]
                    RuleUtil.roleAllowed(rule, scope, roleId) && RuleUtil.classInScope(c, scope)
                }

        return listOf(
            Finding(
                ruleId = RuleUtil.canonicalRuleId(rule),
                message =
                    "Fan-in violations detected: ${violators.size} node(s) " +
                        "exceed max (${params.max}) at granularity '${params.granularity.name.lowercase()}'.",
                filePath = anchor?.let { RuleUtil.filePathOf(it.location) } ?: "",
                severity = rule.severity,
                classFqn = anchor?.fqName,
                memberName = null,
                data =
                    buildMap {
                        put("max", params.max.toString())
                        put("violators", violators.size.toString())
                        put("granularity", params.granularity.name.lowercase())
                        put("includeExternal", params.includeExternal.toString())
                        put("examples", examples)
                        if (violators.size > params.top) put("examplesTruncated", "true")
                    },
            ),
        )
    }

    private data class ReadParams(
        val max: Int,
        val granularity: Granularity,
        val includeExternal: Boolean,
        val top: Int,
    )

    private fun readParams(rule: RuleDef): ReadParams? {
        val p = Params.of(rule.params, path = "rules.${rule.type}.${rule.name}.params")

        val max =
            try {
                p.requireInt("max")
            } catch (_: ParamError) {
                return null
            }
        if (max < 0) return null

        val granularity = parseGranularity(runCatching { p.optionalString("granularity") }.getOrNull())
        val includeExternal = runCatching { p.optionalBoolean("includeExternal") }.getOrNull() ?: false
        val top = runCatching { p.optionalInt("top") }.getOrNull()?.coerceAtLeast(0) ?: 10

        return ReadParams(
            max = max,
            granularity = granularity,
            includeExternal = includeExternal,
            top = top,
        )
    }

    private fun parseGranularity(s: String?): Granularity {
        val v = s?.trim()?.lowercase()
        return when (v) {
            "class" -> Granularity.CLASS
            "module" -> Granularity.MODULE
            "package", null, "" -> Granularity.PACKAGE
            else -> Granularity.PACKAGE
        }
    }
}
