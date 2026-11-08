/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.psi.core.config.validation.v1

import io.shamash.artifacts.params.ParamError
import io.shamash.artifacts.params.Params

internal object MethodCountKinds {
    enum class Kind { DECLARED, PUBLIC, PRIVATE }

    private val wireNames =
        mapOf(
            "declaredMethods" to Kind.DECLARED,
            "publicMethods" to Kind.PUBLIC,
            "privateMethods" to Kind.PRIVATE,
        )

    fun decode(
        params: Map<String, Any?>,
        path: String,
    ): Set<Kind> {
        if ("countKinds" !in params) return setOf(Kind.DECLARED)
        val raw =
            Params.of(params, path).optionalStringList("countKinds")
                ?: throw ParamError("$path.countKinds", "must be a list")
        if (raw.isEmpty()) throw ParamError("$path.countKinds", "must be non-empty")
        return raw
            .mapIndexed { index, value ->
                wireNames[value.trim()] ?: throw ParamError("$path.countKinds[$index]", "Unknown countKind '$value'")
            }.toSet()
    }

    fun wireName(kind: Kind): String = wireNames.entries.first { it.value == kind }.key
}
