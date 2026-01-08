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
package io.shamash.psi.config.validation.v1.params

import java.util.LinkedHashMap

/**
 * Typed parameter reader for rule params.
 *
 * Goals:
 * - Centralize safe coercion + validation of YAML-decoded "Any?" values.
 * - Provide stable, standardized error paths for validator + engine.
 * - Prevent ad-hoc casts in rules ("NO raw casts").
 */
class Params private constructor(
    private val raw: Map<String, Any?>,
    private val path: String,
) {
    companion object {
        fun of(
            raw: Map<String, Any?>,
            path: String = "params",
        ): Params = Params(raw, path)
    }

    /** Stable path accessor for inline methods + external diagnostics. */
    val currentPath: String get() = path

    fun at(newPath: String): Params = Params(raw, newPath)

    fun child(key: String): Params = Params(raw, "$path.$key")

    fun has(key: String): Boolean = raw.containsKey(key)

    fun keys(): Set<String> = raw.keys

    fun unknownKeys(allowed: Set<String>): Set<String> = raw.keys - allowed

    // -------- Scalars --------

    fun requireString(key: String): String {
        val at = "$path.$key"
        val v = raw[key] ?: throw ParamError(at, "is required")
        return v as? String ?: throw ParamError(at, "must be a string")
    }

    fun optionalString(key: String): String? {
        val at = "$path.$key"
        val v = raw[key] ?: return null
        return v as? String ?: throw ParamError(at, "must be a string")
    }

    fun requireBoolean(key: String): Boolean {
        val at = "$path.$key"
        val v = raw[key] ?: throw ParamError(at, "is required")
        return toBoolean(v, at)
    }

    fun optionalBoolean(key: String): Boolean? {
        val at = "$path.$key"
        val v = raw[key] ?: return null
        return toBoolean(v, at)
    }

    fun requireInt(
        key: String,
        min: Int? = null,
        max: Int? = null,
    ): Int {
        val at = "$path.$key"
        val v = raw[key] ?: throw ParamError(at, "is required")
        val n = toInt(v, at)
        if (min != null && n < min) throw ParamError(at, "must be >= $min")
        if (max != null && n > max) throw ParamError(at, "must be <= $max")
        return n
    }

    fun optionalInt(
        key: String,
        min: Int? = null,
        max: Int? = null,
    ): Int? {
        val at = "$path.$key"
        val v = raw[key] ?: return null
        val n = toInt(v, at)
        if (min != null && n < min) throw ParamError(at, "must be >= $min")
        if (max != null && n > max) throw ParamError(at, "must be <= $max")
        return n
    }

    fun requireLong(
        key: String,
        min: Long? = null,
        max: Long? = null,
    ): Long {
        val at = "$path.$key"
        val v = raw[key] ?: throw ParamError(at, "is required")
        val n = toLong(v, at)
        if (min != null && n < min) throw ParamError(at, "must be >= $min")
        if (max != null && n > max) throw ParamError(at, "must be <= $max")
        return n
    }

    fun optionalLong(
        key: String,
        min: Long? = null,
        max: Long? = null,
    ): Long? {
        val at = "$path.$key"
        val v = raw[key] ?: return null
        val n = toLong(v, at)
        if (min != null && n < min) throw ParamError(at, "must be >= $min")
        if (max != null && n > max) throw ParamError(at, "must be <= $max")
        return n
    }

    inline fun <reified E : Enum<E>> requireEnum(
        key: String,
        ignoreCase: Boolean = true,
    ): E {
        val at = "$currentPath.$key"
        val rawStr = requireString(key)
        val normalized = if (ignoreCase) rawStr.trim() else rawStr

        return enumValues<E>().firstOrNull { e ->
            if (ignoreCase) e.name.equals(normalized, ignoreCase = true) else e.name == normalized
        } ?: throw ParamError(at, "must be one of: ${enumValues<E>().joinToString { it.name }}")
    }

    inline fun <reified E : Enum<E>> optionalEnum(
        key: String,
        ignoreCase: Boolean = true,
    ): E? {
        val at = "$currentPath.$key"
        val s = optionalString(key) ?: return null
        val normalized = if (ignoreCase) s.trim() else s

        return enumValues<E>().firstOrNull { e ->
            if (ignoreCase) e.name.equals(normalized, ignoreCase = true) else e.name == normalized
        } ?: throw ParamError(at, "must be one of: ${enumValues<E>().joinToString { it.name }}")
    }

    // -------- Collections --------

    fun requireStringList(
        key: String,
        nonEmpty: Boolean = false,
    ): List<String> {
        val at = "$path.$key"
        val v = raw[key] ?: throw ParamError(at, "is required")
        val list = v as? List<*> ?: throw ParamError(at, "must be a list")
        val out =
            list.mapIndexed { i, it ->
                it as? String ?: throw ParamError("$at[$i]", "must be a string")
            }
        if (nonEmpty && out.isEmpty()) throw ParamError(at, "must be non-empty")
        return out
    }

    fun optionalStringList(
        key: String,
        nonEmpty: Boolean = false,
    ): List<String>? {
        val at = "$path.$key"
        val v = raw[key] ?: return null
        val list = v as? List<*> ?: throw ParamError(at, "must be a list")
        val out =
            list.mapIndexed { i, it ->
                it as? String ?: throw ParamError("$at[$i]", "must be a string")
            }
        if (nonEmpty && out.isEmpty()) throw ParamError(at, "must be non-empty")
        return out
    }

    fun requireStringSet(
        key: String,
        nonEmpty: Boolean = false,
    ): Set<String> = requireStringList(key, nonEmpty).toLinkedHashSet()

    fun optionalStringSet(
        key: String,
        nonEmpty: Boolean = false,
    ): Set<String>? = optionalStringList(key, nonEmpty)?.toLinkedHashSet()

    fun requireMap(key: String): Map<String, Any?> {
        val at = "$path.$key"
        val v = raw[key] ?: throw ParamError(at, "is required")
        val m = v as? Map<*, *> ?: throw ParamError(at, "must be an object/map")

        val out = LinkedHashMap<String, Any?>(m.size)
        for ((k, value) in m) {
            if (k == null) throw ParamError(at, "map key must not be null")
            out[k.toString()] = value
        }
        return out
    }

    fun optionalMap(key: String): Map<String, Any?>? {
        val at = "$path.$key"
        val v = raw[key] ?: return null
        val m = v as? Map<*, *> ?: throw ParamError(at, "must be an object/map")

        val out = LinkedHashMap<String, Any?>(m.size)
        for ((k, value) in m) {
            if (k == null) throw ParamError(at, "map key must not be null")
            out[k.toString()] = value
        }
        return out
    }

    // -------- Converters --------

    private fun toInt(
        v: Any,
        at: String,
    ): Int =
        when (v) {
            is Int -> v
            is Long -> {
                if (v < Int.MIN_VALUE.toLong() || v > Int.MAX_VALUE.toLong()) {
                    throw ParamError(at, "must fit in 32-bit signed integer range")
                }
                v.toInt()
            }
            is Short -> v.toInt()
            is Byte -> v.toInt()
            is Double -> {
                if (!v.isFinite()) throw ParamError(at, "must be a finite number")
                if (v % 1.0 != 0.0) throw ParamError(at, "must be an integer")
                if (v < Int.MIN_VALUE.toDouble() || v > Int.MAX_VALUE.toDouble()) {
                    throw ParamError(at, "must fit in 32-bit signed integer range")
                }
                v.toInt()
            }
            is Float -> {
                if (!v.isFinite()) throw ParamError(at, "must be a finite number")
                if (v % 1.0f != 0.0f) throw ParamError(at, "must be an integer")
                if (v < Int.MIN_VALUE.toFloat() || v > Int.MAX_VALUE.toFloat()) {
                    throw ParamError(at, "must fit in 32-bit signed integer range")
                }
                v.toInt()
            }
            is String -> {
                val s = v.trim()
                if (s.isEmpty()) throw ParamError(at, "must be an integer")
                val n = s.toLongOrNull() ?: throw ParamError(at, "must be an integer")
                if (n < Int.MIN_VALUE.toLong() || n > Int.MAX_VALUE.toLong()) {
                    throw ParamError(at, "must fit in 32-bit signed integer range")
                }
                n.toInt()
            }
            is Number -> {
                val asLong = v.toLong()
                if (asLong < Int.MIN_VALUE.toLong() || asLong > Int.MAX_VALUE.toLong()) {
                    throw ParamError(at, "must fit in 32-bit signed integer range")
                }
                asLong.toInt()
            }
            else -> throw ParamError(at, "must be an integer")
        }

    private fun toLong(
        v: Any,
        at: String,
    ): Long =
        when (v) {
            is Long -> v
            is Int -> v.toLong()
            is Short -> v.toLong()
            is Byte -> v.toLong()
            is Double -> {
                if (!v.isFinite()) throw ParamError(at, "must be a finite number")
                if (v % 1.0 != 0.0) throw ParamError(at, "must be an integer")
                // NOTE: Doubles cannot represent all longs exactly; this is best-effort.
                if (v < Long.MIN_VALUE.toDouble() || v > Long.MAX_VALUE.toDouble()) {
                    throw ParamError(at, "must fit in 64-bit signed integer range")
                }
                v.toLong()
            }
            is Float -> {
                if (!v.isFinite()) throw ParamError(at, "must be a finite number")
                if (v % 1.0f != 0.0f) throw ParamError(at, "must be an integer")
                // Avoid overflow surprises for float -> long.
                val d = v.toDouble()
                if (d < Long.MIN_VALUE.toDouble() || d > Long.MAX_VALUE.toDouble()) {
                    throw ParamError(at, "must fit in 64-bit signed integer range")
                }
                d.toLong()
            }
            is String -> {
                val s = v.trim()
                if (s.isEmpty()) throw ParamError(at, "must be an integer")
                s.toLongOrNull() ?: throw ParamError(at, "must be an integer")
            }
            is Number -> v.toLong()
            else -> throw ParamError(at, "must be an integer")
        }

    private fun toBoolean(
        v: Any,
        at: String,
    ): Boolean =
        when (v) {
            is Boolean -> v
            is String -> {
                when (v.trim().lowercase()) {
                    "true" -> true
                    "false" -> false
                    else -> throw ParamError(at, "must be a boolean (true/false)")
                }
            }
            else -> throw ParamError(at, "must be a boolean (true/false)")
        }

    private fun List<String>.toLinkedHashSet(): Set<String> {
        val set = LinkedHashSet<String>(this.size)
        for (s in this) set.add(s)
        return set
    }
}

class ParamError(
    val at: String,
    override val message: String,
) : RuntimeException("$at $message")
