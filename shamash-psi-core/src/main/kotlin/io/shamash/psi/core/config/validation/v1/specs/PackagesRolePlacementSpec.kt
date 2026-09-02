/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
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
package io.shamash.psi.core.config.validation.v1.specs

import io.shamash.artifacts.params.ParamError
import io.shamash.artifacts.params.Params
import io.shamash.psi.core.config.ValidationError
import io.shamash.psi.core.config.ValidationSeverity
import io.shamash.psi.core.config.schema.v1.model.RuleDef
import io.shamash.psi.core.config.schema.v1.model.RuleKey
import io.shamash.psi.core.config.schema.v1.model.ShamashPsiConfigV1
import io.shamash.psi.core.config.validation.v1.RuleSpec
import java.util.LinkedHashMap

class PackagesRolePlacementSpec : RuleSpec {
    override val key: RuleKey = RuleKey(type = "packages", name = "rolePlacement", role = null)

    private val allowedParamKeys: Set<String> = setOf("expected")
    private val allowedExpectedEntryKeys: Set<String> = setOf("packageRegex")

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

        val expected: Map<String, Any?> =
            try {
                p.requireMap("expected")
            } catch (e: ParamError) {
                err(e.at, e.message)
                return errors
            }

        if (expected.isEmpty()) {
            err("$rulePath.params.expected", "must not be empty")
            return errors
        }

        val orderedExpected = LinkedHashMap<String, Any?>(expected)

        orderedExpected.forEach { (rawRoleId, rawEntry) ->
            val roleId = rawRoleId.trim()
            val entryPath = "$rulePath.params.expected.$rawRoleId"

            if (roleId.isEmpty()) {
                err("$rulePath.params.expected", "role name in expected must be non-empty")
                return@forEach
            }

            if (!config.roles.containsKey(roleId)) {
                err(entryPath, "Unknown role '$roleId'")
            }

            val entryMap =
                rawEntry as? Map<*, *> ?: run {
                    err(entryPath, "must be an object/map")
                    return@forEach
                }

            val normalized = LinkedHashMap<String, Any?>(entryMap.size)
            for ((k, v) in entryMap) {
                if (k == null) {
                    err(entryPath, "map key must not be null")
                    continue
                }
                normalized[k.toString()] = v
            }

            val ep = Params.of(normalized, entryPath)
            val unknownEntry = ep.unknownKeys(allowedExpectedEntryKeys)
            if (unknownEntry.isNotEmpty()) {
                err(entryPath, "Unknown keys: ${unknownEntry.sorted().joinToString(", ")}")
            }

            val pkgRegex =
                try {
                    ep.requireString("packageRegex").trim()
                } catch (e: ParamError) {
                    err(e.at, e.message)
                    null
                }

            if (pkgRegex != null) {
                if (pkgRegex.isEmpty()) {
                    err("$entryPath.packageRegex", "must be non-empty")
                } else {
                    try {
                        Regex(pkgRegex)
                    } catch (_: Throwable) {
                        err("$entryPath.packageRegex", "Invalid regex '$pkgRegex'")
                    }
                }
            }
        }

        return errors
    }
}
