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
package io.shamash.asm.core.engine.rules.api

import io.shamash.artifacts.contract.Finding
import io.shamash.artifacts.params.ParamError
import io.shamash.artifacts.params.Params
import io.shamash.artifacts.util.PathNormalizer
import io.shamash.artifacts.util.glob.GlobMatcher
import io.shamash.asm.core.config.schema.v1.model.RuleDef
import io.shamash.asm.core.config.schema.v1.model.RuleScope
import io.shamash.asm.core.config.schema.v1.model.ShamashAsmConfigV1
import io.shamash.asm.core.engine.rules.Rule
import io.shamash.asm.core.facts.model.FieldRef
import io.shamash.asm.core.facts.model.MethodRef
import io.shamash.asm.core.facts.model.SourceLocation
import io.shamash.asm.core.facts.query.FactIndex
import java.util.regex.Pattern

/**
 * api.forbiddenAnnotationUsage
 *
 * params:
 *   forbid: [ <regex>, ... ]
 *
 * Each regex is matched against multiple identifiers for an annotation type:
 * - fq name:        "com.example.MyAnno"
 * - internal name:  "com/example/MyAnno"
 * - descriptor:     "Lcom/example/MyAnno;"
 *
 * Violations are reported on:
 * - class annotations
 * - method annotations
 * - field annotations
 */
class ForbiddenAnnotationUsageRule : Rule {
    override val id: String = "api.forbiddenAnnotationUsage"

    override fun evaluate(
        facts: FactIndex,
        rule: RuleDef,
        config: ShamashAsmConfigV1,
    ): List<Finding> {
        val patterns = compileForbidPatterns(rule)

        if (patterns.isEmpty()) return emptyList()

        val includePkg = compileRegexList(rule.scope?.includePackages)
        val excludePkg = compileRegexList(rule.scope?.excludePackages)

        val includeGlobs = rule.scope?.includeGlobs?.filter { it.isNotBlank() } ?: emptyList()
        val excludeGlobs = rule.scope?.excludeGlobs?.filter { it.isNotBlank() } ?: emptyList()

        val out = mutableListOf<Finding>()
        val ruleId = ruleKey(rule)
        val severity = rule.severity

        // ---- class-level ----
        for (c in facts.classes) {
            val pkg = c.packageName
            val filePath = filePathOf(c.location)
            if (!inScope(pkg, filePath, includePkg, excludePkg, includeGlobs, excludeGlobs)) continue

            for (ann in c.annotationsFqns) {
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

        // ---- method-level ----
        for (m in facts.methods) {
            val pkg = m.owner.packageName
            val filePath = filePathOf(m.location)
            if (!inScope(pkg, filePath, includePkg, excludePkg, includeGlobs, excludeGlobs)) continue

            for (ann in m.annotationsFqns) {
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

        // ---- field-level ----
        for (f in facts.fields) {
            val pkg = f.owner.packageName
            val filePath = filePathOf(f.location)
            if (!inScope(pkg, filePath, includePkg, excludePkg, includeGlobs, excludeGlobs)) continue

            for (ann in f.annotationsFqns) {
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
        // Config semantic validation already enforces the contract, but we stay defensive.
        val p = Params.of(rule.params, path = "rules.${rule.type}.${rule.name}.params")
        val raw =
            try {
                p.requireStringList("forbid", nonEmpty = true)
            } catch (e: ParamError) {
                // Runtime: treat bad params as "no findings" (engine may also surface an EngineError elsewhere).
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

        // Match against multiple identifiers.
        for (p in patterns) {
            if (p.matcher(fqn).find()) return p.pattern()
            if (p.matcher(internal).find()) return p.pattern()
            if (p.matcher(desc).find()) return p.pattern()
        }
        return null
    }

    private fun ruleKey(rule: RuleDef): String =
        // role is handled by engine via ruleId decoration (if you expand per-role).
        "${rule.type.trim()}.${rule.name.trim()}"

    private fun compileRegexList(list: List<String>?): List<Regex> =
        list
            ?.mapNotNull { it.trim().takeIf(String::isNotEmpty) }
            ?.map { Regex(it) }
            ?: emptyList()

    private fun inScope(
        packageName: String,
        filePath: String,
        includePkg: List<Regex>,
        excludePkg: List<Regex>,
        includeGlobs: List<String>,
        excludeGlobs: List<String>,
    ): Boolean {
        if (includePkg.isNotEmpty() && includePkg.none { it.containsMatchIn(packageName) }) return false
        if (excludePkg.isNotEmpty() && excludePkg.any { it.containsMatchIn(packageName) }) return false

        val fp = GlobMatcher.normalizePath(filePath)
        if (includeGlobs.isNotEmpty() && includeGlobs.none { GlobMatcher.matches(it, fp) }) return false
        if (excludeGlobs.isNotEmpty() && excludeGlobs.any { GlobMatcher.matches(it, fp) }) return false

        return true
    }

    private fun filePathOf(loc: SourceLocation?): String {
        if (loc == null) return ""
        val n = loc.normalized()
        val container = n.containerPath
        val entry = n.entryPath
        val base =
            when {
                container != null && entry != null -> "${PathNormalizer.normalize(container)}!/${entry.replace('\\', '/')}"
                else -> PathNormalizer.normalize(n.originPath)
            }
        return base
    }
}
