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

class DeadcodeUnusedPrivateMembersSpec : RuleSpec {
    override val id: String = "deadcode.unusedPrivateMembers"

    override fun validate(
        rulePath: String,
        rule: Rule,
        config: ShamashPsiConfigV1,
    ): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()
        val p = rule.params

        // check object optional
        val checkAny = p["check"]
        if (checkAny != null) {
            val checkMap = checkAny as? Map<*, *>
            if (checkMap == null) {
                errors +=
                    RuleValidationHelpers.err(
                        "$rulePath.check",
                        "check must be an object like { fields: true, methods: true, classes: false }",
                    )
            } else {
                val cm = checkMap.entries.associate { it.key.toString() to it.value }
                // optional booleans
                RuleValidationHelpers.optionalBoolean(cm, "fields", "$rulePath.check", errors)
                RuleValidationHelpers.optionalBoolean(cm, "methods", "$rulePath.check", errors)
                RuleValidationHelpers.optionalBoolean(cm, "classes", "$rulePath.check", errors)
            }
        }

        // ignore lists optional
        RuleValidationHelpers.optionalStringList(p, "ignoreIfAnnotatedWithExact", rulePath, errors)
        RuleValidationHelpers.optionalStringList(p, "ignoreIfAnnotatedWithPrefix", rulePath, errors)
        RuleValidationHelpers.optionalStringList(p, "ignoreIfContainingClassAnnotatedWithExact", rulePath, errors)
        RuleValidationHelpers.optionalStringList(p, "ignoreIfContainingClassAnnotatedWithPrefix", rulePath, errors)

        val ignoreRoles = RuleValidationHelpers.optionalStringList(p, "ignoreRoles", rulePath, errors)
        ignoreRoles?.forEachIndexed { i, r ->
            RuleValidationHelpers.checkRoleExists(r, "$rulePath.ignoreRoles[$i]", config, errors)
        }

        val ignoreNameRegex = RuleValidationHelpers.optionalStringList(p, "ignoreNameRegex", rulePath, errors)
        ignoreNameRegex?.forEachIndexed { i, rx ->
            RuleValidationHelpers.compileRegex(rx, "$rulePath.ignoreNameRegex[$i]", errors)
        }

        return errors
    }
}
