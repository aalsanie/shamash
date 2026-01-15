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

/**
 * Spec for `deadcode.unusedPrivateMembers`.
 *
 * Params:
 * - `check`: optional object with booleans: `{ fields?: bool, methods?: bool, classes?: bool }`
 * - `ignoreIfAnnotatedWithExact`: optional non-empty list of annotation FQNs
 * - `ignoreIfAnnotatedWithPrefix`: optional non-empty list of annotation prefixes
 * - `ignoreIfContainingClassAnnotatedWithExact`: optional non-empty list
 * - `ignoreIfContainingClassAnnotatedWithPrefix`: optional non-empty list
 * - `ignoreRoles`: optional list of existing roleIds
 * - `ignoreNameRegex`: optional list of regex strings (must compile)
 */
class DeadcodeUnusedPrivateMembersSpec : RuleSpec {
    override val key: RuleKey = RuleKey(type = "deadcode", name = "unusedPrivateMembers", role = null)

    private val allowedParamKeys: Set<String> =
        setOf(
            "check",
            "ignoreIfAnnotatedWithExact",
            "ignoreIfAnnotatedWithPrefix",
            "ignoreIfContainingClassAnnotatedWithExact",
            "ignoreIfContainingClassAnnotatedWithPrefix",
            "ignoreRoles",
            "ignoreNameRegex",
        )

    private val allowedCheckKeys: Set<String> = setOf("fields", "methods", "classes")

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

        // check: optional object
        val checkRaw = rule.params["check"]
        if (checkRaw != null) {
            val checkMap =
                checkRaw as? Map<*, *> ?: run {
                    err(
                        "$rulePath.params.check",
                        "must be an object like { fields: true, methods: true, classes: false }",
                    )
                    null
                }

            if (checkMap != null) {
                val ordered = LinkedHashMap<String, Any?>(checkMap.size)
                for ((k, v) in checkMap) {
                    if (k == null) {
                        err("$rulePath.params.check", "map key must not be null")
                        continue
                    }
                    ordered[k.toString()] = v
                }
                val cp = Params.of(ordered, "$rulePath.params.check")

                val unknownCheck = cp.unknownKeys(allowedCheckKeys)
                if (unknownCheck.isNotEmpty()) {
                    err("$rulePath.params.check", "Unknown keys: ${unknownCheck.sorted().joinToString(", ")}")
                }

                try {
                    cp.optionalBoolean("fields")
                    cp.optionalBoolean("methods")
                    cp.optionalBoolean("classes")
                } catch (e: ParamError) {
                    err(e.at, e.message)
                }
            }
        }

        fun optionalNonBlankStringList(key: String): List<String>? {
            val list =
                try {
                    p.optionalStringList(key)
                } catch (e: ParamError) {
                    err(e.at, e.message)
                    return null
                }
            list?.forEachIndexed { i, s ->
                if (s.isBlank()) {
                    err("$rulePath.params.$key[$i]", "must be non-empty")
                }
            }
            return list?.map { it.trim() }
        }

        // ignore lists (optional)
        optionalNonBlankStringList("ignoreIfAnnotatedWithExact")
        optionalNonBlankStringList("ignoreIfAnnotatedWithPrefix")
        optionalNonBlankStringList("ignoreIfContainingClassAnnotatedWithExact")
        optionalNonBlankStringList("ignoreIfContainingClassAnnotatedWithPrefix")

        // ignoreRoles: optional list of role ids; must exist
        val ignoreRoles = optionalNonBlankStringList("ignoreRoles")
        ignoreRoles?.forEachIndexed { i, r ->
            val rid = r.trim()
            if (rid.isNotEmpty() && !config.roles.containsKey(rid)) {
                err("$rulePath.params.ignoreRoles[$i]", "Unknown role '$rid'")
            }
        }

        // ignoreNameRegex: optional list; must compile
        val ignoreNameRegex = optionalNonBlankStringList("ignoreNameRegex")
        ignoreNameRegex?.forEachIndexed { i, rxRaw ->
            val rx = rxRaw.trim()
            if (rx.isNotEmpty()) {
                try {
                    Regex(rx)
                } catch (_: Throwable) {
                    err("$rulePath.params.ignoreNameRegex[$i]", "Invalid regex '$rx'")
                }
            }
        }

        return errors
    }
}
