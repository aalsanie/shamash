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
package io.shamash.psi.ui.config

import io.shamash.psi.config.schema.v1.registry.RuleSpecRegistryV1
import io.shamash.psi.engine.registry.RuleRegistry

object PsiRuleSupportView {
    data class Support(
        val executable: Set<String>,
        val specd: Set<String>,
        val missingSpec: Set<String>,
        val missingRule: Set<String>,
    )

    fun compute(): Support {
        val executable = RuleRegistry.allIds()
        val specd = RuleSpecRegistryV1.allIds()

        return Support(
            executable = executable,
            specd = specd,
            missingSpec = executable - specd,
            missingRule = specd - executable,
        )
    }

    fun format(s: Support): String =
        buildString {
            append("Executable rules:\n")
            s.executable.sorted().forEach { append("- ").append(it).append('\n') }

            append("\nSpec-validated rules:\n")
            s.specd.sorted().forEach { append("- ").append(it).append('\n') }

            if (s.missingSpec.isNotEmpty()) {
                append("\nWARNING: Rules missing specs (will run but schema validation is weaker):\n")
                s.missingSpec.sorted().forEach { append("- ").append(it).append('\n') }
            }

            if (s.missingRule.isNotEmpty()) {
                append("\nWARNING: Specs without rules (validate-able but won’t run):\n")
                s.missingRule.sorted().forEach { append("- ").append(it).append('\n') }
            }
        }.trimEnd()
}
