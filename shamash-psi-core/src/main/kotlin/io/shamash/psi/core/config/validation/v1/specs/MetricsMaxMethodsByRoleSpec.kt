/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.psi.core.config.validation.v1.specs

import io.shamash.artifacts.params.ParamError
import io.shamash.artifacts.params.Params
import io.shamash.psi.core.config.ValidationError
import io.shamash.psi.core.config.ValidationSeverity
import io.shamash.psi.core.config.schema.v1.model.RuleDef
import io.shamash.psi.core.config.schema.v1.model.RuleKey
import io.shamash.psi.core.config.schema.v1.model.ShamashPsiConfigV1
import io.shamash.psi.core.config.validation.v1.RuleSpec

class MetricsMaxMethodsByRoleSpec : RuleSpec {
    override val key: RuleKey = RuleKey(type = "metrics", name = "maxMethodsByRole", role = null)

    private val allowedParamKeys: Set<String> = setOf("limits", "countKinds", "ignoreMethodNameRegex")

    private val allowedCountKinds: Set<String> =
        setOf(
            "declaredMethods",
            "publicMethods",
            "privateMethods",
        )

    override fun validate(
        rulePath: String,
        rule: RuleDef,
        config: ShamashPsiConfigV1,
    ): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()
        val p = Params.of(rule.params, "$rulePath.params")

        fun err(
            at: String,
            message: String,
        ) {
            errors += ValidationError(at, message, ValidationSeverity.ERROR)
        }

        val unknown = p.unknownKeys(allowedParamKeys)
        if (unknown.isNotEmpty()) {
            err("$rulePath.params", "Unknown params: ${unknown.sorted().joinToString(", ")}")
        }

        val limits =
            try {
                p.requireMap("limits")
            } catch (e: ParamError) {
                err(e.at, e.message)
                return errors
            }

        if (limits.isEmpty()) {
            err("$rulePath.params.limits", "must not be empty")
            return errors
        }

        limits.entries.forEach { (rawRoleId, rawLimit) ->
            val roleId = rawRoleId.trim()
            val entryPath = "$rulePath.params.limits.$rawRoleId"

            if (roleId.isEmpty()) {
                err("$rulePath.params.limits", "role name in limits must be non-empty")
                return@forEach
            }
            if (!config.roles.containsKey(roleId)) {
                err(entryPath, "Unknown role '$roleId'")
            }

            try {
                Params.of(mapOf("v" to rawLimit), entryPath).requireInt("v", min = 0)
            } catch (e: ParamError) {
                err(e.at, e.message)
            }
        }

        val countKinds =
            try {
                p.optionalStringList("countKinds")
            } catch (e: ParamError) {
                err(e.at, e.message)
                null
            }

        countKinds?.forEachIndexed { i, raw ->
            val k = raw.trim()
            if (k.isEmpty()) {
                err("$rulePath.params.countKinds[$i]", "must be non-empty")
            } else if (k !in allowedCountKinds) {
                err(
                    "$rulePath.params.countKinds[$i]",
                    "Unknown countKind '$k'. Allowed: ${allowedCountKinds.sorted().joinToString(", ")}",
                )
            }
        }

        val ignoreRegexes =
            try {
                p.optionalStringList("ignoreMethodNameRegex")
            } catch (e: ParamError) {
                err(e.at, e.message)
                null
            }

        ignoreRegexes?.forEachIndexed { i, raw ->
            val rx = raw.trim()
            if (rx.isEmpty()) {
                err("$rulePath.params.ignoreMethodNameRegex[$i]", "must be non-empty")
                return@forEachIndexed
            }
            try {
                Regex(rx)
            } catch (_: Throwable) {
                err("$rulePath.params.ignoreMethodNameRegex[$i]", "Invalid regex '$rx'")
            }
        }

        return errors
    }
}
