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
package io.shamash.psi.fixes.providers

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import io.shamash.psi.engine.Finding
import io.shamash.psi.fixes.FixContext
import io.shamash.psi.fixes.FixProvider
import io.shamash.psi.fixes.ShamashFix

class MetricsMaxMethodsByRoleFixProvider : FixProvider {
    override fun supports(f: Finding): Boolean = f.ruleId == "metrics.maxMethodsByRole"

    override fun fixesFor(
        f: Finding,
        ctx: FixContext,
    ): List<ShamashFix> {
        val role = f.data["role"] ?: return emptyList()
        val actual = f.data["actual"]?.toIntOrNull() ?: return emptyList()
        val fixes = mutableListOf<ShamashFix>()

        // Config-edit fix (only if the config file is known)
        val cfgVf = ctx.configFile
        if (cfgVf != null) {
            fixes += IncreaseMaxInConfigFix(ctx.project, cfgVf, role, actual)
        }
        return fixes
    }

    private class IncreaseMaxInConfigFix(
        private val project: com.intellij.openapi.project.Project,
        private val cfg: com.intellij.openapi.vfs.VirtualFile,
        private val role: String,
        private val newMax: Int,
    ) : ShamashFix {
        override val id: String = "metrics.config.max.$role.$newMax"
        override val title: String = "Set max methods for role '$role' to $newMax (config)"

        override fun isApplicable(): Boolean = cfg.isValid

        override fun apply() {
            val psiFile = PsiManager.getInstance(project).findFile(cfg) ?: return
            val doc = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return

            val text = doc.text
            // Very pragmatic YAML rewrite. We look for:
            // rules:
            //   metrics.maxMethodsByRole:
            //     role: <role>
            //     max: <n>
            // and replace the max value.

            // Find the rule block start
            val ruleStart = Regex("(?m)^\\s*metrics\\.maxMethodsByRole\\s*:\\s*$").find(text) ?: return
            val from = ruleStart.range.last + 1
            val tail = text.substring(from)

            // Ensure we're editing the right role block (role key appears before max)
            val roleMatch = Regex("(?m)^\\s*role\\s*:\\s*\\Q$role\\E\\s*$").find(tail) ?: return
            val maxMatch = Regex("(?m)^\\s*max\\s*:\\s*(\\d+)\\s*$").find(tail) ?: return
            if (maxMatch.range.first < roleMatch.range.first) {
                // role appears after max — ambiguous block; bail.
                return
            }

            val maxAbsStart = from + maxMatch.range.first
            val maxAbsEnd = from + maxMatch.range.last + 1

            val replacement = maxMatch.value.replace(Regex("\\d+"), newMax.toString())
            WriteCommandAction.runWriteCommandAction(project) {
                doc.replaceString(maxAbsStart, maxAbsEnd, replacement)
                PsiDocumentManager.getInstance(project).commitDocument(doc)
            }
        }
    }
}
