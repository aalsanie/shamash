/*
 * Copyright © 2025-2026 | Shamash
 *
 * Shamash is a JVM architecture enforcement tool that helps teams
 * define, validate, and continuously enforce architectural boundaries.
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
package io.shamash.asm.core.config.validation.v1.specs

import io.shamash.artifacts.params.ParamError
import io.shamash.artifacts.params.Params
import io.shamash.asm.core.config.ValidationError
import io.shamash.asm.core.config.ValidationSeverity
import io.shamash.asm.core.config.schema.v1.model.RuleDef
import io.shamash.asm.core.config.schema.v1.model.RuleKey
import io.shamash.asm.core.config.schema.v1.model.ShamashAsmConfigV1
import io.shamash.asm.core.config.validation.v1.RuleSpec
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * api.forbiddenAnnotationUsage
 *
 * Non-opinionated param contract:
 *
 * params:
 *   forbid: [ <regex>, ... ]
 *
 * Where each entry is a regex that matches an annotation type identifier
 * (engine-defined: could be internal name, binary name, descriptor, etc).
 *
 * This spec only validates:
 * - param presence / types
 * - non-empty list
 * - regex compilation
 * - unknown params (ERROR)
 */
class ApiForbiddenAnnotationUsageSpec : RuleSpec {
    override val key: RuleKey = RuleKey(type = "api", name = "forbiddenAnnotationUsage", role = null)

    override fun validate(
        rulePath: String,
        rule: RuleDef,
        config: ShamashAsmConfigV1,
    ): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()
        val paramsPath = "$rulePath.params"
        val p = Params.of(rule.params, path = paramsPath)

        val allowed = setOf("forbid")

        // Unknown keys: ERROR
        p.unknownKeys(allowed).forEach { k ->
            errors +=
                ValidationError(
                    path = "$paramsPath.$k",
                    message = "Unknown param '$k'. Allowed params: ${allowed.joinToString()}",
                    severity = ValidationSeverity.ERROR,
                )
        }

        try {
            // forbid: required, non-empty list<string>
            val raw = rule.params["forbid"] ?: throw ParamError("$paramsPath.forbid", "is required")
            val list = raw as? List<*> ?: throw ParamError("$paramsPath.forbid", "must be a list")
            if (list.isEmpty()) throw ParamError("$paramsPath.forbid", "must be non-empty")

            list.forEachIndexed { i, v ->
                val at = "$paramsPath.forbid[$i]"
                val rx = (v as? String)?.trim() ?: throw ParamError(at, "must be a string")
                if (rx.isEmpty()) throw ParamError(at, "must be non-empty")
                compileRegex(rx, at, errors)
            }
        } catch (e: ParamError) {
            errors +=
                ValidationError(
                    path = e.at,
                    message = e.message,
                    severity = ValidationSeverity.ERROR,
                )
        }

        return errors
    }

    private fun compileRegex(
        rx: String,
        path: String,
        errors: MutableList<ValidationError>,
    ) {
        try {
            Pattern.compile(rx)
        } catch (e: PatternSyntaxException) {
            errors +=
                ValidationError(
                    path = path,
                    message = "Invalid regex: ${e.description}",
                    severity = ValidationSeverity.ERROR,
                )
        }
    }
}
