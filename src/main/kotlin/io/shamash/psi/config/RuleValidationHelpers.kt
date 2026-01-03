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
package io.shamash.psi.config

import io.shamash.psi.config.schema.v1.model.ShamashPsiConfigV1
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

internal object RuleValidationHelpers {
    fun err(
        path: String,
        msg: String,
    ): ValidationError = ValidationError(path = path, message = msg, severity = ValidationSeverity.ERROR)

    fun warn(
        path: String,
        msg: String,
    ): ValidationError = ValidationError(path = path, message = msg, severity = ValidationSeverity.WARNING)

    fun requireString(
        map: Map<String, Any?>,
        key: String,
        path: String,
        errors: MutableList<ValidationError>,
    ): String? {
        val v = map[key]
        if (v == null) {
            errors += err("$path.$key", "Missing required field '$key'")
            return null
        }
        if (v !is String || v.isBlank()) {
            errors += err("$path.$key", "Field '$key' must be a non-empty string")
            return null
        }
        return v
    }

    fun optionalString(
        map: Map<String, Any?>,
        key: String,
        path: String,
        errors: MutableList<ValidationError>,
    ): String? {
        val v = map[key] ?: return null
        if (v !is String || v.isBlank()) {
            errors += err("$path.$key", "Field '$key' must be a non-empty string if provided")
            return null
        }
        return v
    }

    fun optionalBoolean(
        map: Map<String, Any?>,
        key: String,
        path: String,
        errors: MutableList<ValidationError>,
    ): Boolean? {
        val v = map[key] ?: return null
        if (v !is Boolean) {
            errors += err("$path.$key", "Field '$key' must be boolean if provided")
            return null
        }
        return v
    }

    fun optionalInt(
        map: Map<String, Any?>,
        key: String,
        path: String,
        errors: MutableList<ValidationError>,
    ): Int? {
        val v = map[key] ?: return null
        val n = (v as? Number)?.toInt()
        if (n == null) {
            errors += err("$path.$key", "Field '$key' must be an integer if provided")
            return null
        }
        return n
    }

    fun requireStringList(
        map: Map<String, Any?>,
        key: String,
        path: String,
        errors: MutableList<ValidationError>,
    ): List<String>? {
        val v = map[key]
        if (v == null) {
            errors += err("$path.$key", "Missing required field '$key'")
            return null
        }
        val list =
            (v as? List<*>)?.mapNotNull { it as? String } ?: run {
                errors += err("$path.$key", "Field '$key' must be a list of strings")
                return null
            }
        if (list.isEmpty() || list.any { it.isBlank() }) {
            errors +=
                err(
                    "$path.$key",
                    "Field '$key' " +
                        "must be a non-empty list of non-empty strings",
                )
            return null
        }
        return list
    }

    fun optionalStringList(
        map: Map<String, Any?>,
        key: String,
        path: String,
        errors: MutableList<ValidationError>,
    ): List<String>? {
        val v = map[key] ?: return null
        val list =
            (v as? List<*>)?.mapNotNull { it as? String } ?: run {
                errors += err("$path.$key", "Field '$key' must be a list of strings")
                return null
            }
        if (list.any { it.isBlank() }) {
            errors += err("$path.$key", "Field '$key' must contain only non-empty strings")
            return null
        }
        return list
    }

    fun requireMapList(
        map: Map<String, Any?>,
        key: String,
        path: String,
        errors: MutableList<ValidationError>,
    ): List<Map<String, Any?>>? {
        val v = map[key]
        if (v == null) {
            errors += err("$path.$key", "Missing required field '$key'")
            return null
        }
        val list = v as? List<*>
        if (list == null) {
            errors += err("$path.$key", "Field '$key' must be a list of objects")
            return null
        }
        val out = mutableListOf<Map<String, Any?>>()
        list.forEachIndexed { i, item ->
            val m = item as? Map<*, *>
            if (m == null) {
                errors += err("$path.$key[$i]", "Each item must be an object")
            } else {
                @Suppress("UNCHECKED_CAST")
                out += (m.entries.associate { it.key.toString() to it.value })
            }
        }
        return out
    }

    fun compileRegex(
        rx: String,
        path: String,
        errors: MutableList<ValidationError>,
    ) {
        try {
            Pattern.compile(rx)
        } catch (e: PatternSyntaxException) {
            errors += err(path, "Invalid regex: ${e.description}")
        }
    }

    fun checkRoleExists(
        role: String,
        rolePath: String,
        config: ShamashPsiConfigV1,
        errors: MutableList<ValidationError>,
    ) {
        if (!config.roles.containsKey(role)) {
            errors += err(rolePath, "Unknown role '$role' (not defined in roles)")
        }
    }

    fun checkAllRolesExist(
        roles: List<String>,
        basePath: String,
        config: ShamashPsiConfigV1,
        errors: MutableList<ValidationError>,
    ) {
        roles.forEachIndexed { i, r -> checkRoleExists(r, "$basePath[$i]", config, errors) }
    }

    fun requireNonEmptyParams(
        rulePath: String,
        params: Map<String, Any?>,
        errors: MutableList<ValidationError>,
    ) {
        if (params.isEmpty()) {
            errors += warn(rulePath, "Rule has no parameters. This may be intentional, but often indicates a misconfiguration.")
        }
    }
}
