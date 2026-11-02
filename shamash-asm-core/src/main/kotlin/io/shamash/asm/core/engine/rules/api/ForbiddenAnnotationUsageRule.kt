/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.asm.core.engine.rules.api

import io.shamash.artifacts.contract.Finding
import io.shamash.artifacts.params.ParamError
import io.shamash.artifacts.params.Params
import io.shamash.asm.core.config.schema.v1.model.RuleDef
import io.shamash.asm.core.config.schema.v1.model.ShamashAsmConfigV1
import io.shamash.asm.core.engine.rules.Rule
import io.shamash.asm.core.engine.rules.RuleUtil
import io.shamash.asm.core.facts.query.FactIndex
import java.util.regex.Pattern

/** Matches class, method, and field annotation regexes against FQNs, JVM internal names, and descriptors. */
class ForbiddenAnnotationUsageRule : Rule {
    override val id: String = "api.forbiddenAnnotationUsage"

    override fun evaluate(
        facts: FactIndex,
        rule: RuleDef,
        config: ShamashAsmConfigV1,
    ): List<Finding> {
        val patterns = compileForbidPatterns(rule)

        if (patterns.isEmpty()) return emptyList()

        val scope = RuleUtil.compileScope(rule.scope)
        val owners = facts.classes.associateBy { it.fqName }

        val out = mutableListOf<Finding>()
        val ruleId = ruleKey(rule)
        val severity = rule.severity

        for (c in facts.classes.sortedBy { it.fqName }) {
            if (!RuleUtil.ownerAllowed(c, rule, scope, facts.classToRole)) continue
            val filePath = RuleUtil.filePathOf(c.location)

            for (ann in c.annotationsFqns.sorted()) {
                val matched = matchesAny(patterns, ann) ?: continue
                out +=
                    Finding(
                        ruleId = ruleId,
                        message = "Forbidden annotation '$ann' used on class '${c.fqName}' (matched: $matched)",
                        filePath = filePath,
                        severity = severity,
                        classFqn = c.fqName,
                        memberName = null,
                        data =
                            mapOf(
                                "targetKind" to "class",
                                "annotationFqn" to ann,
                                "matchedPattern" to matched,
                                "classInternalName" to c.type.internalName,
                            ),
                    )
            }
        }

        for (m in facts.methods.sortedWith(compareBy({ it.owner.fqName }, { it.name }, { it.desc }))) {
            val owner = owners[m.owner.fqName] ?: continue
            if (!RuleUtil.ownerAllowed(owner, rule, scope, facts.classToRole)) continue
            val filePath = RuleUtil.filePathOf(m.location)

            for (ann in m.annotationsFqns.sorted()) {
                val matched = matchesAny(patterns, ann) ?: continue
                val ownerFqn = m.owner.fqName
                out +=
                    Finding(
                        ruleId = ruleId,
                        message = "Forbidden annotation '$ann' used on method '$ownerFqn#${m.name}' (matched: $matched)",
                        filePath = filePath,
                        severity = severity,
                        classFqn = ownerFqn,
                        memberName = m.name,
                        data =
                            mapOf(
                                "targetKind" to "method",
                                "annotationFqn" to ann,
                                "matchedPattern" to matched,
                                "classInternalName" to m.owner.internalName,
                                "memberDesc" to m.desc,
                            ),
                    )
            }
        }

        for (f in facts.fields.sortedWith(compareBy({ it.owner.fqName }, { it.name }, { it.desc }))) {
            val owner = owners[f.owner.fqName] ?: continue
            if (!RuleUtil.ownerAllowed(owner, rule, scope, facts.classToRole)) continue
            val filePath = RuleUtil.filePathOf(f.location)

            for (ann in f.annotationsFqns.sorted()) {
                val matched = matchesAny(patterns, ann) ?: continue
                val ownerFqn = f.owner.fqName
                out +=
                    Finding(
                        ruleId = ruleId,
                        message = "Forbidden annotation '$ann' used on field '$ownerFqn#${f.name}' (matched: $matched)",
                        filePath = filePath,
                        severity = severity,
                        classFqn = ownerFqn,
                        memberName = f.name,
                        data =
                            mapOf(
                                "targetKind" to "field",
                                "annotationFqn" to ann,
                                "matchedPattern" to matched,
                                "classInternalName" to f.owner.internalName,
                                "memberDesc" to f.desc,
                            ),
                    )
            }
        }

        return out
    }

    private fun compileForbidPatterns(rule: RuleDef): List<Pattern> {
        val p = Params.of(rule.params, path = "rules.${rule.type}.${rule.name}.params")
        val raw =
            try {
                p.requireStringList("forbid", nonEmpty = true)
            } catch (e: ParamError) {
                return emptyList()
            }

        return raw
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { Pattern.compile(it) }
    }

    private fun matchesAny(
        patterns: List<Pattern>,
        annotationFqn: String,
    ): String? {
        val fqn = annotationFqn.trim()
        if (fqn.isEmpty()) return null

        val internal = fqn.replace('.', '/')
        val desc = "L$internal;"

        for (p in patterns) {
            if (p.matcher(fqn).find()) return p.pattern()
            if (p.matcher(internal).find()) return p.pattern()
            if (p.matcher(desc).find()) return p.pattern()
        }
        return null
    }

    private fun ruleKey(rule: RuleDef): String = RuleUtil.canonicalRuleId(rule)
}
