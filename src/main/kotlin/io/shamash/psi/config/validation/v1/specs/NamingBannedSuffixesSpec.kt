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
 * Spec for `naming.bannedSuffixes`.
 *
 * Params:
 * - `banned`: required non-empty list of suffix strings.
 * - `applyToRoles`: optional list of existing roleIds.
 * - `caseSensitive`: optional boolean.
 */
class NamingBannedSuffixesSpec : RuleSpec {
    override val key: RuleKey = RuleKey(type = "naming", name = "bannedSuffixes", role = null)

    private val allowedParamKeys: Set<String> = setOf("banned", "applyToRoles", "caseSensitive")

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

        // banned: required non-empty list
        val banned =
            try {
                p.requireStringList("banned", nonEmpty = true)
            } catch (e: ParamError) {
                err(e.at, e.message)
                return errors
            }

        banned.forEachIndexed { i, raw ->
            val s = raw.trim()
            if (s.isEmpty()) {
                err("$rulePath.params.banned[$i]", "must be non-empty")
            }
        }

        // applyToRoles: optional list of existing roleIds
        val roles =
            try {
                p.optionalStringList("applyToRoles")
            } catch (e: ParamError) {
                err(e.at, e.message)
                null
            }

        roles?.forEachIndexed { i, rawRoleId ->
            val roleId = rawRoleId.trim()
            if (roleId.isEmpty()) {
                err("$rulePath.params.applyToRoles[$i]", "must be non-empty")
            } else if (!config.roles.containsKey(roleId)) {
                err("$rulePath.params.applyToRoles[$i]", "Unknown role '$roleId'")
            }
        }

        // caseSensitive: optional boolean
        try {
            p.optionalBoolean("caseSensitive")
        } catch (e: ParamError) {
            err(e.at, e.message)
        }

        return errors
    }
}
