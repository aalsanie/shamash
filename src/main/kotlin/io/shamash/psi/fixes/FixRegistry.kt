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
package io.shamash.psi.fixes

import io.shamash.psi.engine.Finding
import io.shamash.psi.fixes.providers.ArchForbiddenRoleDependenciesFixProvider
import io.shamash.psi.fixes.providers.DeadcodeUnusedPrivateMembersFixProvider
import io.shamash.psi.fixes.providers.MetricsMaxMethodsByRoleFixProvider
import io.shamash.psi.fixes.providers.NamingBannedSuffixesFixProvider
import io.shamash.psi.fixes.providers.PackagesRolePlacementFixProvider
import io.shamash.psi.fixes.providers.PackagesRootPackageFixProvider
import io.shamash.psi.fixes.providers.SuppressFixProvider

object FixRegistry {
    private val providers =
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
    ): List<ShamashFix> = providers.filter { it.supports(f) }.flatMap { it.fixesFor(f, ctx) }
}
