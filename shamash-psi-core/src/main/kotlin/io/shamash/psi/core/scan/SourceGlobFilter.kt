/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
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
package io.shamash.psi.core.scan

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
