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
import io.shamash.asm.core.config.schema.v1.model.RuleDef
import io.shamash.asm.core.config.schema.v1.model.ShamashAsmConfigV1
import io.shamash.asm.core.engine.rules.Rule
import io.shamash.asm.core.engine.rules.RuleUtil
import io.shamash.asm.core.facts.model.DependencyEdge
import io.shamash.asm.core.facts.query.FactIndex
import java.util.LinkedHashMap
import java.util.LinkedHashSet

/**
 * metrics.maxPackageSpread
 *
 * Params:
 * - max: int (required, >= 0)
 * - includeExternal: boolean (optional, default false)
 * - includeSelf: boolean (optional, default false)
 * - top: int (optional, default 20) // number of violators to include in examples list
 *
 * Definition:
 * - For each PACKAGE P, compute the number of distinct packages it depends on (outgoing),
 *   based on class dependency edges aggregated to package level:
 *       spread(P) = | { pkg(to) } |
 *
 * Semantics:
 * - Package scope is applied using producing class (from-class) via RuleUtil scope filters.
 * - If includeExternal=false, ignore dependencies to packages with no classes in facts.
 * - If includeSelf=false, ignore dependencies where pkg(from) == pkg(to).
 * - If any package spread > max -> emit a single finding listing top violators.
 */
class MaxPackageSpreadRule : Rule {
    override val id: String = "metrics.maxPackageSpread"

    override fun evaluate(
        facts: FactIndex,
        rule: RuleDef,
        config: ShamashAsmConfigV1,
    ): List<Finding> {
        val params = readParams(rule) ?: return emptyList()
        val scope = RuleUtil.compileScope(rule.scope)

        if (facts.edges.isEmpty() || facts.classes.isEmpty()) return emptyList()

        // Set of project packages (present in facts.classes).
        val projectPackages: Set<String> =
            facts.classes
                .asSequence()
                .map { it.packageName }
                .toSet()

        // Map of classFqn -> (packageName, inScope)
        val classPkg = facts.classes.associate { it.fqName to it.packageName }
        val inScopeFromClass =
            facts.classes.associate { c ->
                val roleId = facts.classToRole[c.fqName]
                c.fqName to (RuleUtil.roleAllowed(rule, scope, roleId) && RuleUtil.classInScope(c, scope))
            }

        // Compute spread: package -> set<package>
        val spread = LinkedHashMap<String, LinkedHashSet<String>>()

        val edges =
            facts.edges.sortedWith(
                compareBy<DependencyEdge>({ it.from.fqName }, { it.to.fqName }, { it.kind.name }, { it.detail ?: "" }),
            )

        for (e in edges) {
            // anchor on from-class scope
            if (inScopeFromClass[e.from.fqName] != true) continue

            val fromPkg = classPkg[e.from.fqName] ?: e.from.packageName
            val toPkg = classPkg[e.to.fqName] ?: e.to.packageName

            if (!params.includeSelf && fromPkg == toPkg) continue

            // external handling: if includeExternal=false, only count toPkg if it's in projectPackages.
            if (!params.includeExternal && toPkg !in projectPackages) continue

            spread.getOrPut(fromPkg) { LinkedHashSet() }.add(toPkg)
        }

        // Compute violating packages
        val violators =
            spread
                .map { (pkg, tos) -> pkg to tos.size }
                .filter { (_, cnt) -> cnt > params.max }
                .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })

        if (violators.isEmpty()) return emptyList()

        val top = violators.take(params.top)
        val examples = top.joinToString(",") { (p, c) -> "$p:$c" }

        // Anchor on first class in-scope (stable).
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
                message = "Package spread violations detected: ${violators.size} package(s) exceed max (${params.max}).",
                filePath = anchor?.let { RuleUtil.filePathOf(it.location) } ?: "",
                severity = rule.severity,
                classFqn = anchor?.fqName,
                memberName = null,
                data =
                    buildMap {
                        put("max", params.max.toString())
                        put("violators", violators.size.toString())
                        put("includeExternal", params.includeExternal.toString())
                        put("includeSelf", params.includeSelf.toString())
                        put("examples", examples)
                        if (violators.size > params.top) put("examplesTruncated", "true")
                    },
            ),
        )
    }

    private data class ReadParams(
        val max: Int,
        val includeExternal: Boolean,
        val includeSelf: Boolean,
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

        val includeExternal = runCatching { p.optionalBoolean("includeExternal") }.getOrNull() ?: false
        val includeSelf = runCatching { p.optionalBoolean("includeSelf") }.getOrNull() ?: false
        val top = runCatching { p.optionalInt("top") }.getOrNull()?.coerceAtLeast(0) ?: 20

        return ReadParams(
            max = max,
            includeExternal = includeExternal,
            includeSelf = includeSelf,
            top = top,
        )
    }
}
