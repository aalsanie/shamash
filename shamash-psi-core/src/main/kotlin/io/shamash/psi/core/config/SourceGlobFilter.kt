/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.psi.core.config

import io.shamash.artifacts.util.glob.GlobMatcher

internal object SourceGlobFilter {
    fun matches(
        path: String,
        include: List<String>,
        exclude: List<String>,
    ): Boolean =
        (include.isEmpty() || include.any { GlobMatcher.matches(it, path) }) &&
            exclude.none { GlobMatcher.matches(it, path) }
}
