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
package io.shamash.psi.core.fixes

import io.shamash.artifacts.contract.Finding
import io.shamash.psi.core.fixes.providers.ArchForbiddenRoleDependenciesFixProvider
import io.shamash.psi.core.fixes.providers.DeadcodeUnusedPrivateMembersFixProvider
import io.shamash.psi.core.fixes.providers.MetricsMaxMethodsByRoleFixProvider
import io.shamash.psi.core.fixes.providers.NamingBannedSuffixesFixProvider
import io.shamash.psi.core.fixes.providers.PackagesRolePlacementFixProvider
import io.shamash.psi.core.fixes.providers.PackagesRootPackageFixProvider
import io.shamash.psi.core.fixes.providers.SuppressFixProvider

object FixRegistry {
    private val providers: List<FixProvider> =
        listOf(
            ArchForbiddenRoleDependenciesFixProvider(),
            DeadcodeUnusedPrivateMembersFixProvider(),
            MetricsMaxMethodsByRoleFixProvider(),
            NamingBannedSuffixesFixProvider(),
            PackagesRolePlacementFixProvider(),
            PackagesRootPackageFixProvider(),
            SuppressFixProvider(),
        )

    fun fixesFor(
        f: Finding,
        ctx: FixContext,
    ): List<ShamashFix> {
        val out = ArrayList<ShamashFix>(8)
        val seenIds = HashSet<String>(8)

        for (p in providers) {
            val supported = runCatching { p.supports(f) }.getOrDefault(false)
            if (!supported) continue

            val fixes = runCatching { p.fixesFor(f, ctx) }.getOrDefault(emptyList())
            for (fix in fixes) {
                // de-dup by id to keep UI stable even if providers overlap
                if (seenIds.add(fix.id)) out += fix
            }
        }

        return out
    }
}
