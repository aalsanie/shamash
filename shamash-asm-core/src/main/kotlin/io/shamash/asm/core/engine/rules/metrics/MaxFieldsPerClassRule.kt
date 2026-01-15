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
import io.shamash.asm.core.facts.model.FieldRef
import io.shamash.asm.core.facts.query.FactIndex

/**
 * metrics.maxFieldsPerClass
 *
 * Params:
 * - max: int (required, >= 0)
 * - examples: int (optional, default 10) // include up to N field fqNames as examples per violating class
 *
 * Semantics:
 * - Counts declared fields per class (bytecode facts).
 * - For each in-scope class, if fieldCount > max -> emit a finding for that class.
 */
class MaxFieldsPerClassRule : Rule {
    override val id: String = "metrics.maxFieldsPerClass"

    override fun evaluate(
        facts: FactIndex,
        rule: RuleDef,
        config: ShamashAsmConfigV1,
    ): List<Finding> {
        val params = readParams(rule) ?: return emptyList()
        val scope = RuleUtil.compileScope(rule.scope)

        if (facts.classes.isEmpty() || facts.fields.isEmpty()) return emptyList()

        // Group fields by owner class FQN deterministically.
        val fieldsByOwner: Map<String, List<FieldRef>> =
            facts.fields
                .asSequence()
                .sortedWith(compareBy<FieldRef>({ it.owner.fqName }, { it.name }, { it.desc }))
                .groupBy { it.owner.fqName }

        val out = ArrayList<Finding>()

        // Deterministic iteration over classes.
        val classes = facts.classes.sortedBy { it.fqName }
        for (c in classes) {
            val roleId = facts.classToRole[c.fqName]
            if (!RuleUtil.roleAllowed(rule, scope, roleId)) continue
            if (!RuleUtil.classInScope(c, scope)) continue

            val fields = fieldsByOwner[c.fqName].orEmpty()
            val count = fields.size
            if (count <= params.max) continue

            val exampleList =
                fields
                    .asSequence()
                    .map { it.fqName }
                    .distinct()
                    .sorted()
                    .take(params.examples)
                    .toList()

            out +=
                Finding(
                    ruleId = RuleUtil.canonicalRuleId(rule),
                    message = "Field count ($count) exceeds max (${params.max}) for class '${c.fqName}'.",
                    filePath = RuleUtil.filePathOf(c.location),
                    severity = rule.severity,
                    classFqn = c.fqName,
                    memberName = null,
                    data =
                        buildMap {
                            put("max", params.max.toString())
                            put("count", count.toString())
                            put("roleId", roleId ?: "")
                            put("classInternalName", c.type.internalName)
                            if (exampleList.isNotEmpty()) {
                                put("examples", exampleList.joinToString(","))
                                if (count > params.examples) put("examplesTruncated", "true")
                            }
                        },
                )
        }

        return out
    }

    private data class ReadParams(
        val max: Int,
        val examples: Int,
    )

    private fun readParams(rule: RuleDef): ReadParams? {
        val p = Params.of(rule.params, path = "rules.${rule.type}.${rule.name}.params")

        val max: Int =
            try {
                p.requireInt("max")
            } catch (_: ParamError) {
                return null
            }
        if (max < 0) return null

        val examples = runCatching { p.optionalInt("examples") }.getOrNull()?.coerceAtLeast(0) ?: 10

        return ReadParams(max = max, examples = examples)
    }
}
