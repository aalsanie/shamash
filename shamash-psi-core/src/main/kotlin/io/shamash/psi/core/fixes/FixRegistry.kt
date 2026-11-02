/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
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
                if (seenIds.add(fix.id)) out += fix
            }
        }

        return out
    }
}
