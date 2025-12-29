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

class PackagesRolePlacementSpec : RuleSpec {
    override val id: String = "packages.rolePlacement"

    override fun validate(
        rulePath: String,
        rule: Rule,
        config: ShamashPsiConfigV1,
    ): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()
        val p = rule.params

        val expectedAny = p["expected"]
        val expectedMap = expectedAny as? Map<*, *>
        if (expectedMap == null) {
            errors +=
                RuleValidationHelpers.err(
                    "$rulePath.expected",
                    "Missing or invalid 'expected' map (role -> {packageRegex})",
                )
            return errors
        }
        if (expectedMap.isEmpty()) {
            errors += RuleValidationHelpers.err("$rulePath.expected", "'expected' must not be empty")
            return errors
        }

        expectedMap.entries.forEach { (rk, rv) ->
            val role = rk?.toString() ?: ""
            val entryPath = "$rulePath.expected.$role"
            if (role.isBlank()) {
                errors += RuleValidationHelpers.err("$rulePath.expected", "Role name in expected must be non-empty")
                return@forEach
            }
            RuleValidationHelpers.checkRoleExists(role, entryPath, config, errors)

            val obj = rv as? Map<*, *>
            if (obj == null) {
                errors +=
                    RuleValidationHelpers.err(
                        entryPath,
                        "Expected value must be an object like { packageRegex: \"...\" }",
                    )
                return@forEach
            }
            val map = obj.entries.associate { it.key.toString() to it.value }

            val rx = RuleValidationHelpers.requireString(map, "packageRegex", entryPath, errors)
            if (rx != null) {
                RuleValidationHelpers.compileRegex(rx, "$entryPath.packageRegex", errors)
            }
        }

        return errors
    }
}
