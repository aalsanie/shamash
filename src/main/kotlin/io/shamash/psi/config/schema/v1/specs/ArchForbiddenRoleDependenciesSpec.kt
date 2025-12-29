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

class ArchForbiddenRoleDependenciesSpec : RuleSpec {
    override val id: String = "arch.forbiddenRoleDependencies"

    private val allowedDependencyKinds =
        setOf(
            "methodCall",
            "fieldType",
            "parameterType",
            "returnType",
            "extends",
            "implements",
            "annotationType",
        )

    override fun validate(
        rulePath: String,
        rule: Rule,
        config: ShamashPsiConfigV1,
    ): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()
        val p = rule.params

        // forbidden is required and must be a list of objects
        val forbidden = RuleValidationHelpers.requireMapList(p, "forbidden", rulePath, errors) ?: return errors
        if (forbidden.isEmpty()) {
            errors +=
                RuleValidationHelpers.err(
                    "$rulePath.forbidden",
                    "Must contain at least one forbidden dependency rule",
                )
        }

        forbidden.forEachIndexed { i, entry ->
            val entryPath = "$rulePath.forbidden[$i]"
            val from = RuleValidationHelpers.requireString(entry, "from", entryPath, errors)
            val toList = RuleValidationHelpers.requireStringList(entry, "to", entryPath, errors)

            if (from != null) {
                RuleValidationHelpers.checkRoleExists(from, "$entryPath.from", config, errors)
            }
            if (toList != null) {
                RuleValidationHelpers.checkAllRolesExist(toList, "$entryPath.to", config, errors)
            }

            // message optional, but if present must be non-empty
            RuleValidationHelpers.optionalString(entry, "message", entryPath, errors)
        }

        // dependencyKinds optional
        val kinds = RuleValidationHelpers.optionalStringList(p, "dependencyKinds", rulePath, errors)
        if (kinds != null) {
            kinds.forEachIndexed { i, k ->
                if (k !in allowedDependencyKinds) {
                    errors +=
                        RuleValidationHelpers.err(
                            "$rulePath.dependencyKinds[$i]",
                            "Unknown dependencyKind '$k'. Allowed: ${allowedDependencyKinds.sorted().joinToString(", ")}",
                        )
                }
            }
        }

        return errors
    }
}
