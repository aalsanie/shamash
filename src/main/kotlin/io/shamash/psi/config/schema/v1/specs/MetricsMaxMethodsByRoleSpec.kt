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
package io.shamash.psi.config.schema.v1.specs

import io.shamash.psi.config.RuleValidationHelpers
import io.shamash.psi.config.ValidationError
import io.shamash.psi.config.schema.v1.RuleSpec
import io.shamash.psi.config.schema.v1.model.Rule
import io.shamash.psi.config.schema.v1.model.ShamashPsiConfigV1

class MetricsMaxMethodsByRoleSpec : RuleSpec {
    override val id: String = "metrics.maxMethodsByRole"

    private val allowedCountKinds =
        setOf(
            "declaredMethods",
            "publicMethods",
            "privateMethods",
        )

    override fun validate(
        rulePath: String,
        rule: Rule,
        config: ShamashPsiConfigV1,
    ): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()
        val p = rule.params

        val limitsAny = p["limits"]
        val limitsMap = limitsAny as? Map<*, *>
        if (limitsMap == null) {
            errors += RuleValidationHelpers.err("$rulePath.limits", "Missing or invalid 'limits' map (role -> integer)")
            return errors
        }
        if (limitsMap.isEmpty()) {
            errors += RuleValidationHelpers.err("$rulePath.limits", "'limits' must not be empty")
            return errors
        }

        limitsMap.entries.forEach { (k, v) ->
            val role = k?.toString() ?: ""
            val value = (v as? Number)?.toInt()
            val entryPath = "$rulePath.limits.$role"
            if (role.isBlank()) {
                errors += RuleValidationHelpers.err("$rulePath.limits", "Role name in limits must be non-empty")
                return@forEach
            }
            RuleValidationHelpers.checkRoleExists(role, entryPath, config, errors)
            if (value == null || value < 0) {
                errors += RuleValidationHelpers.err(entryPath, "Limit must be a non-negative integer")
            }
        }

        val countKinds = RuleValidationHelpers.optionalStringList(p, "countKinds", rulePath, errors)
        if (countKinds != null) {
            countKinds.forEachIndexed { i, k ->
                if (k !in allowedCountKinds) {
                    errors +=
                        RuleValidationHelpers.err(
                            "$rulePath.countKinds[$i]",
                            "Unknown countKind '$k'. Allowed: ${allowedCountKinds.sorted().joinToString(", ")}",
                        )
                }
            }
        }

        val ignoreRegexes = RuleValidationHelpers.optionalStringList(p, "ignoreMethodNameRegex", rulePath, errors)
        ignoreRegexes?.forEachIndexed { i, rx ->
            RuleValidationHelpers.compileRegex(rx, "$rulePath.ignoreMethodNameRegex[$i]", errors)
        }

        return errors
    }
}
