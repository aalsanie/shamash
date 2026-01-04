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
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import io.shamash.psi.config.schema.v1.model.RootPackageModeV1
import io.shamash.psi.engine.Finding
import io.shamash.psi.fixes.FixContext
import io.shamash.psi.fixes.FixProvider
import io.shamash.psi.fixes.PsiResolver
import io.shamash.psi.fixes.ShamashFix

/**
 * Fixes for packages.rootPackage violations.
 *
 * Provides two pragmatic fixes:
 *  1) Update the offending source file's package statement to be under the configured root package.
 *     Example: expected "com.myco", actual "a"  -> "com.myco.a"
 *
 *  2) Update the config YAML rootPackage.value to match the file's actual package (when configFile is available).
 *
 * NOTE:
 * - This provider relies on config.project.rootPackage when EXPLICIT; otherwise falls back to finding data or no-op.
 * - This is intentionally "best effort" and avoids deep YAML parsing.
 */
class PackagesRootPackageFixProvider : FixProvider {
    override fun supports(f: Finding): Boolean = f.ruleId == RULE_ID

    override fun fixesFor(
        f: Finding,
        ctx: FixContext,
    ): List<ShamashFix> {
        val project = ctx.project
        val psiFile = PsiResolver.resolveFile(project, f.filePath) ?: return emptyList()

        val actualPkg = readDeclaredPackage(project, psiFile) ?: return emptyList()

        val expectedRoot =
            expectedRootPackage(ctx, f)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: return emptyList()

        val fixes = ArrayList<ShamashFix>(2)

        // Fix #1: change source file package
        val newPkg =
            if (actualPkg.startsWith("$expectedRoot.")) {
                // already under root - nothing to do
                null
            } else if (actualPkg == expectedRoot) {
                null
            } else {
                // Place current package under expected root.
                // Example: a -> com.myco.a
                "$expectedRoot.$actualPkg"
            }

        if (newPkg != null) {
            fixes += ChangeFilePackageFix(project, psiFile, newPkg)
        }

        // Fix #2: update config to match actual package (if we have configFile)
        val cfgVf = ctx.configFile
        if (cfgVf != null) {
            fixes += SetRootPackageInConfigFix(project, cfgVf, actualPkg)
        }

        return fixes
    }

    private fun expectedRootPackage(
        ctx: FixContext,
        f: Finding,
    ): String? {
        // Prefer config.project.rootPackage when EXPLICIT
        val cfg = ctx.config
        if (cfg != null) {
            val rp = cfg.project.rootPackage
            if (rp.mode == RootPackageModeV1.EXPLICIT && rp.value.isNotBlank()) {
                return rp.value
            }
        }

        // fallback: rule params or finding data
        val fromFinding =
            f.data["expectedRootPackage"]
                ?: f.data["rootPackage"]
                ?: f.data["expectedRoot"]
        if (!fromFinding.isNullOrBlank()) return fromFinding

        // fallback: try rule params if present (common pattern)
        val rule = cfg?.rules?.get(RULE_ID)
        val p = rule?.params?.get("value") as? String
        return p
    }

    private fun readDeclaredPackage(
        project: Project,
        file: PsiFile,
    ): String? {
        val doc = PsiDocumentManager.getInstance(project).getDocument(file) ?: return null
        val text = doc.text ?: return null

        // Kotlin + Java compatible
        val m =
            Regex(
                "(?m)^\\s*package\\s+([a-zA-Z0-9_\\.]+)\\s*;?\\s*$",
            ).find(text) ?: return null
        return m.groupValues.getOrNull(1)?.trim()
    }

    /**
     * Public top-level fix class (NOT private nested),
     * because FixesPanel previously invoked fixes via reflection.
     */
    class ChangeFilePackageFix(
        private val project: Project,
        private val file: PsiFile,
        private val newPackage: String,
    ) : ShamashFix {
        override val id: String = "packages.rootPackage.changeFile.$newPackage"
        override val title: String = "Move file package to: $newPackage"

        override fun isApplicable(): Boolean = file.isValid

        override fun apply() {
            val doc = PsiDocumentManager.getInstance(project).getDocument(file) ?: return
            val text = doc.text ?: return
            val isKotlin = file.virtualFile?.extension?.lowercase() == "kt"

            val pkgRegex = Regex("^\\s*package\\s+([a-zA-Z0-9_\\.]+)\\s*;?", setOf(RegexOption.MULTILINE))
            val m = pkgRegex.find(text)

            WriteCommandAction.runWriteCommandAction(project) {
                if (m != null) {
                    val range = m.range
                    val replacement = if (isKotlin) "package $newPackage" else "package $newPackage;"
                    doc.replaceString(range.first, range.last + 1, replacement)
                } else {
                    val insertion = if (isKotlin) "package $newPackage\n\n" else "package $newPackage;\n\n"
                    doc.insertString(0, insertion)
                }
                PsiDocumentManager.getInstance(project).commitDocument(doc)
            }
        }
    }

    /**
     * Updates YAML:
     * - project.rootPackage.value
     * - AND (if present) rules.packages.rootPackage.params.value
     *
     * Best-effort text rewrite. No YAML parser required.
     */
    class SetRootPackageInConfigFix(
        private val project: Project,
        private val cfg: VirtualFile,
        private val newRootPackage: String,
    ) : ShamashFix {
        override val id: String = "packages.rootPackage.setConfig.$newRootPackage"
        override val title: String = "Set config rootPackage to: $newRootPackage"

        override fun isApplicable(): Boolean = cfg.isValid

        override fun apply() {
            val psiFile = PsiManager.getInstance(project).findFile(cfg) ?: return
            val doc = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return
            val text = doc.text ?: return

            // 1) project.rootPackage.value
            val updated1 = rewriteProjectRootPackageValue(text, newRootPackage)

            // 2) rules.packages.rootPackage.params.value (optional)
            val updated2 = rewriteRuleRootPackageValue(updated1, newRootPackage)

            if (updated2 == text) return

            WriteCommandAction.runWriteCommandAction(project) {
                doc.replaceString(0, doc.textLength, updated2)
                PsiDocumentManager.getInstance(project).commitDocument(doc)
            }
        }

        private fun rewriteProjectRootPackageValue(
            text: String,
            value: String,
        ): String {
            // Locate "project:" then "rootPackage:" then first "value:"
            val projectStart = Regex("(?m)^\\s*project\\s*:\\s*$").find(text) ?: return text
            val from = projectStart.range.last + 1
            val tail = text.substring(from)

            val rootStart = Regex("(?m)^\\s*rootPackage\\s*:\\s*$").find(tail) ?: return text
            val rootFrom = from + rootStart.range.last + 1
            val rootTail = text.substring(rootFrom)

            val valueMatch = Regex("(?m)^\\s*value\\s*:\\s*.*$").find(rootTail) ?: return text
            val absStart = rootFrom + valueMatch.range.first
            val absEnd = rootFrom + valueMatch.range.last + 1

            val replacement = valueMatch.value.replace(Regex("(?m)(^\\s*value\\s*:\\s*).*$"), "$1\"$value\"")
            return text.substring(0, absStart) + replacement + text.substring(absEnd)
        }

        private fun rewriteRuleRootPackageValue(
            text: String,
            value: String,
        ): String {
            // Very pragmatic: find rule header "packages.rootPackage:" then look for "value:" in its block
            val ruleStart = Regex("(?m)^\\s*packages\\.rootPackage\\s*:\\s*$").find(text) ?: return text
            val from = ruleStart.range.last + 1
            val tail = text.substring(from)

            // Find value line within the next ~40 lines or until next top-level rule key.
            // We will rewrite the first "value:" we see inside that region.
            val windowEnd =
                run {
                    val nextRule = Regex("(?m)^\\s{2}[a-zA-Z0-9_\\.\\-]+\\s*:\\s*$").find(tail)
                    // If we find another rule at same indent (2 spaces), stop before it.
                    nextRule?.range?.first ?: minOf(tail.length, 4000)
                }

            val window = tail.substring(0, windowEnd)
            val valueMatch = Regex("(?m)^\\s*value\\s*:\\s*.*$").find(window) ?: return text

            val absStart = from + valueMatch.range.first
            val absEnd = from + valueMatch.range.last + 1

            val replacement = valueMatch.value.replace(Regex("(?m)(^\\s*value\\s*:\\s*).*$"), "$1\"$value\"")
            return text.substring(0, absStart) + replacement + text.substring(absEnd)
        }
    }

    companion object {
        private const val RULE_ID = "packages.rootPackage"
    }
}
