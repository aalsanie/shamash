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
import java.util.regex.Pattern

class PackagesRootPackageSpec : RuleSpec {
    override val id: String = "packages.rootPackage"

    private val pkgPattern = Pattern.compile("^[a-zA-Z_]\\w*(\\.[a-zA-Z_]\\w*)*$")

    override fun validate(
        rulePath: String,
        rule: Rule,
        config: ShamashPsiConfigV1,
    ): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()
        val p = rule.params

        val modeRaw = (p["mode"] as? String)?.lowercase() ?: "auto"
        if (modeRaw !in setOf("auto", "explicit")) {
            errors += RuleValidationHelpers.err("$rulePath.mode", "mode must be 'auto' or 'explicit'")
            return errors
        }

        val value = p["value"] as? String

        if (modeRaw == "explicit") {
            if (value.isNullOrBlank()) {
                errors += RuleValidationHelpers.err("$rulePath.value", "value is required when mode=explicit")
            } else if (!pkgPattern.matcher(value).matches()) {
                errors += RuleValidationHelpers.err("$rulePath.value", "value must look like a Java package name (e.g. com.myco)")
            }
        } else {
            // mode=auto: value should be empty or a hint
            // warn if it's set
            if (!value.isNullOrBlank()) {
                errors += RuleValidationHelpers.warn("$rulePath.value", "value is ignored when mode=auto")
            }
        }

        return errors
    }
}
