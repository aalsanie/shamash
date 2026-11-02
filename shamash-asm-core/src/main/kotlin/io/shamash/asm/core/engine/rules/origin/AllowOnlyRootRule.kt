/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.asm.core.engine.rules.origin

import io.shamash.artifacts.contract.Finding
import io.shamash.artifacts.params.ParamError
import io.shamash.asm.core.config.schema.v1.model.RuleDef
import io.shamash.asm.core.config.schema.v1.model.ShamashAsmConfigV1
import io.shamash.asm.core.config.validation.v1.OriginRootsParams
import io.shamash.asm.core.engine.rules.ContextAwareRule
import io.shamash.asm.core.engine.rules.RuleExecutionContext
import io.shamash.asm.core.engine.rules.RuleUtil
import io.shamash.asm.core.facts.model.OriginKind
import io.shamash.asm.core.facts.query.FactIndex
import java.nio.file.Files
import java.nio.file.Path

class AllowOnlyRootRule : ContextAwareRule {
    override val id = "origin.allowOnlyRoot"

    override fun evaluate(
        facts: FactIndex,
        rule: RuleDef,
        config: ShamashAsmConfigV1,
    ): List<Finding> = evaluate(facts, rule, config, RuleExecutionContext(null))

    override fun evaluate(
        facts: FactIndex,
        rule: RuleDef,
        config: ShamashAsmConfigV1,
        context: RuleExecutionContext,
    ): List<Finding> {
        val path = "rules.${rule.type}.${rule.name}.params"
        val allowed = OriginRootsParams.roots(rule.params, path).map { resolve(it, "$path.roots", context, config) }
        val scope = RuleUtil.compileScope(rule.scope)
        return facts.classes.sortedBy { it.fqName }.mapNotNull { clazz ->
            val role = facts.classToRole[clazz.fqName]
            if (!RuleUtil.roleAllowed(rule, scope, role)) return@mapNotNull null

            val location = clazz.location
            val origin =
                if (location.originKind == OriginKind.JAR_ENTRY) location.containerPath ?: location.originPath else location.originPath
            if (origin.isBlank()) throw ParamError("$path.roots", "cannot evaluate class '${clazz.fqName}' with missing bytecode origin")
            val actual = resolve(origin, "$path.roots", context, config)
            if (!RuleUtil.classInScope(clazz, scope)) return@mapNotNull null
            if (allowed.any { actual.startsWith(it) }) return@mapNotNull null

            Finding(
                ruleId = RuleUtil.canonicalRuleId(rule),
                message = "Class '${clazz.fqName}' has bytecode origin outside allowed roots: $actual",
                filePath = RuleUtil.filePathOf(location),
                severity = rule.severity,
                classFqn = clazz.fqName,
                memberName = null,
                data = mapOf("originPath" to actual.toString(), "roots" to allowed.joinToString(",") { it.toString() }),
            )
        }
    }

    private fun resolve(
        raw: String,
        at: String,
        context: RuleExecutionContext,
        config: ShamashAsmConfigV1,
    ): Path {
        val path =
            try {
                Path.of(raw).normalize()
            } catch (e: java.nio.file.InvalidPathException) {
                throw ParamError(at, "invalid filesystem path: '$raw'")
            }
        val absolute =
            if (path.isAbsolute) {
                path
            } else {
                val base =
                    context.projectBasePath
                        ?: throw ParamError(at, "relative origin paths require explicit projectBasePath")
                base.toAbsolutePath().normalize().resolve(path)
            }
        val normalized = absolute.normalize()
        return if (config.project.scan.followSymlinks && Files.exists(normalized)) {
            normalized.toRealPath()
        } else {
            normalized
        }
    }
}
