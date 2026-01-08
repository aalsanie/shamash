/*
 * Copyright © 2025-2026 | Shamash is a refactoring tool that enforces clean architecture.
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
package io.shamash.psi.config.validation.v1.specs

import io.shamash.psi.config.ValidationError
import io.shamash.psi.config.ValidationSeverity
import io.shamash.psi.config.schema.v1.model.RuleDef
import io.shamash.psi.config.schema.v1.model.RuleKey
import io.shamash.psi.config.schema.v1.model.ShamashPsiConfigV1
import io.shamash.psi.config.validation.v1.RuleSpec
import io.shamash.psi.config.validation.v1.params.ParamError
import io.shamash.psi.config.validation.v1.params.Params

/**
 * Spec for `metrics.maxMethodsByRole`.
 *
 * Params:
 * - `limits`: required non-empty map of `{ roleId: <non-negative int> }`.
 * - `countKinds`: optional list of counting strategies.
 * - `ignoreMethodNameRegex`: optional list of regex strings (must compile).
 */
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

        // Unknown params (typos)
        val unknown = p.unknownKeys(allowedParamKeys)
        if (unknown.isNotEmpty()) {
            err("$rulePath.params", "Unknown params: ${unknown.sorted().joinToString(", ")}")
        }

        // limits: required map(roleId -> int)
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

        // Validate each (roleId -> limit)
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
                // Read single value via a scoped Params; enforces numeric coercion & >= 0.
                Params.of(mapOf("v" to rawLimit), entryPath).requireInt("v", min = 0)
            } catch (e: ParamError) {
                err(e.at, e.message)
            }
        }

        // countKinds: optional list
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

        // ignoreMethodNameRegex: optional list; must compile
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
