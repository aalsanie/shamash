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

/**
 * Spec for `arch.forbiddenRoleDependencies`.
 *
 * Params:
 * - `kinds`: optional list of dependency kinds to consider.
 * - `forbidden`: required non-empty list of objects:
 *   - `from`: roleId
 *   - `to`: non-empty list of roleIds
 *   - `message`: optional custom message (must be non-blank)
 */
class ArchForbiddenRoleDependenciesSpec : RuleSpec {
    override val key: RuleKey = RuleKey(type = "arch", name = "forbiddenRoleDependencies", role = null)

    private val allowedParamKeys: Set<String> = setOf("kinds", "forbidden")
    private val allowedForbiddenEntryKeys: Set<String> = setOf("from", "to", "message")

    /**
     * Allowed dependency kinds that the engine can emit/understand.
     * Keep this list in lockstep with the engine's dependency model.
     */
    private val allowedKinds: Set<String> =
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

        // kinds: optional list of strings
        val kinds =
            try {
                p.optionalStringList("kinds")
            } catch (e: ParamError) {
                err(e.at, e.message)
                null
            }

        kinds?.forEachIndexed { i, raw ->
            val k = raw.trim()
            if (k.isEmpty()) {
                err("$rulePath.params.kinds[$i]", "must be non-empty")
            } else if (k !in allowedKinds) {
                err(
                    "$rulePath.params.kinds[$i]",
                    "Unknown kind '$k'. Allowed: ${allowedKinds.sorted().joinToString(", ")}",
                )
            }
        }

        // forbidden: required list of objects
        val forbiddenList: List<*> =
            when (val raw = rule.params["forbidden"]) {
                null -> {
                    err("$rulePath.params.forbidden", "is required")
                    return errors
                }
                is List<*> -> raw
                else -> {
                    err("$rulePath.params.forbidden", "must be a list")
                    return errors
                }
            }

        if (forbiddenList.isEmpty()) {
            err("$rulePath.params.forbidden", "must contain at least one entry")
            return errors
        }

        forbiddenList.forEachIndexed { i, item ->
            val entryPath = "$rulePath.params.forbidden[$i]"
            val entry =
                item as? Map<*, *> ?: run {
                    err(entryPath, "must be an object/map")
                    return@forEachIndexed
                }

            val entryMap = entry.entries.associate { (k, v) -> k.toString() to v }
            val ep = Params.of(entryMap, entryPath)

            val unknownEntryKeys = ep.unknownKeys(allowedForbiddenEntryKeys)
            if (unknownEntryKeys.isNotEmpty()) {
                err(entryPath, "Unknown keys: ${unknownEntryKeys.sorted().joinToString(", ")}")
            }

            val from =
                try {
                    ep.requireString("from").trim()
                } catch (e: ParamError) {
                    err(e.at, e.message)
                    null
                }

            val to =
                try {
                    ep.requireStringList("to", nonEmpty = true).map { it.trim() }
                } catch (e: ParamError) {
                    err(e.at, e.message)
                    null
                }

            if (from != null) {
                if (from.isEmpty()) {
                    err("$entryPath.from", "must be non-empty")
                } else if (!config.roles.containsKey(from)) {
                    err("$entryPath.from", "Unknown role '$from'")
                }
            }

            if (to != null) {
                to.forEachIndexed { j, ridRaw ->
                    val rid = ridRaw.trim()
                    if (rid.isEmpty()) {
                        err("$entryPath.to[$j]", "must be non-empty")
                    } else if (!config.roles.containsKey(rid)) {
                        err("$entryPath.to[$j]", "Unknown role '$rid'")
                    }
                }
            }

            val message =
                try {
                    ep.optionalString("message")?.trim()
                } catch (e: ParamError) {
                    err(e.at, e.message)
                    null
                }

            if (message != null && message.isEmpty()) {
                err("$entryPath.message", "must be non-empty if present")
            }
        }

        return errors
    }
}
