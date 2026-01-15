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

/**
 * Spec: arch.forbiddenRoleDependencies
 *
 * params:
 *   forbidden:
 *     <fromRole>: [<toRole>, <toRole>, ...]
 *     <fromRole2>: [ ... ]
 *   direction: "direct" | "transitive"   # optional; validated if present
 */
class ArchForbiddenRoleDependenciesSpec : RuleSpec {
    override val key: RuleKey = RuleKey(type = "arch", name = "forbiddenRoleDependencies", role = null)

    init {
        require(key.role == null) { "RuleSpec.key.role must be null. Got: $key" }
    }

    override fun validate(
        rulePath: String,
        rule: RuleDef,
        config: ShamashAsmConfigV1,
    ): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()
        val p = Params.of(rule.params, path = "$rulePath.params")

        // Strict allowed keys
        val allowed = setOf("forbidden", "direction")
        val unknown = p.unknownKeys(allowed)
        if (unknown.isNotEmpty()) {
            errors +=
                ValidationError(
                    path = p.currentPath,
                    message =
                        "Unknown params keys: ${unknown.sorted().joinToString(", ")}. " +
                            "Allowed: ${allowed.sorted().joinToString(", ")}",
                    severity = ValidationSeverity.ERROR,
                )
            // show all failures at once
        }

        // direction (optional)
        val direction =
            try {
                p.optionalString("direction")?.trim()
            } catch (e: ParamError) {
                errors += err(e.at, e.message)
                null
            }

        if (direction != null) {
            val ok = direction.equals("direct", ignoreCase = true) || direction.equals("transitive", ignoreCase = true)
            if (!ok) {
                errors += err("${p.currentPath}.direction", "must be one of: direct, transitive")
            }
        }

        // forbidden: required map<fromRole, list<toRole>>
        val forbiddenMap: Map<String, Any?> =
            try {
                p.requireMap("forbidden")
            } catch (e: ParamError) {
                errors += err(e.at, e.message)
                return errors
            }

        if (forbiddenMap.isEmpty()) {
            errors += err("${p.currentPath}.forbidden", "must contain at least one entry")
            return errors
        }

        val seenPairs = HashSet<Pair<String, String>>()

        forbiddenMap.entries.forEach { (rawFrom, rawToListAny) ->
            val fromRole = rawFrom.trim()
            val fromPath = "${p.currentPath}.forbidden.${escapeKey(rawFrom)}"

            if (fromRole.isEmpty()) {
                errors += err(fromPath, "fromRole key must be non-empty")
                return@forEach
            }

            if (!config.roles.containsKey(fromRole)) {
                errors += err(fromPath, "Unknown role '$fromRole' (not defined under roles)")
            }

            val toList: List<*> =
                when (rawToListAny) {
                    is List<*> -> rawToListAny
                    null -> {
                        errors += err(fromPath, "must be a list of role ids")
                        return@forEach
                    }
                    else -> {
                        errors += err(fromPath, "must be a list of role ids")
                        return@forEach
                    }
                }

            if (toList.isEmpty()) {
                errors += err(fromPath, "must be a non-empty list of role ids")
                return@forEach
            }

            val localSeen = HashSet<String>()
            toList.forEachIndexed { idx, v ->
                val at = "$fromPath[$idx]"
                val toRole = (v as? String)?.trim()
                if (toRole == null) {
                    errors += err(at, "must be a string role id")
                    return@forEachIndexed
                }
                if (toRole.isEmpty()) {
                    errors += err(at, "role id must be non-empty")
                    return@forEachIndexed
                }

                if (!localSeen.add(toRole)) {
                    errors += err(at, "duplicate role '$toRole' in list")
                }

                if (!config.roles.containsKey(toRole)) {
                    errors += err(at, "Unknown role '$toRole' (not defined under roles)")
                }

                if (fromRole == toRole) {
                    errors += err(at, "fromRole must not forbid itself ('$fromRole')")
                }

                val pair = fromRole to toRole
                if (!seenPairs.add(pair)) {
                    errors += err(at, "duplicate forbidden dependency '$fromRole -> $toRole'")
                }
            }
        }

        return errors
    }

    private fun err(
        path: String,
        msg: String,
    ): ValidationError = ValidationError(path = path, message = msg, severity = ValidationSeverity.ERROR)

    /**
     * Best-effort escape for map keys in error paths.
     * (Avoids breaking paths when keys contain '.' etc.)
     */
    private fun escapeKey(key: String): String {
        val k = key.trim()
        if (k.isEmpty()) return "\"\""
        val safe = k.replace("\"", "\\\"")
        return "\"$safe\""
    }
}
