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
package io.shamash.psi.engine.rules

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiFile
import io.shamash.psi.config.schema.v1.model.RuleDef
import io.shamash.psi.config.schema.v1.model.ShamashPsiConfigV1
import io.shamash.psi.config.validation.v1.params.Params
import io.shamash.psi.engine.EngineRule
import io.shamash.psi.engine.Finding
import io.shamash.psi.facts.model.v1.FactsIndex

/**
 * Rule: packages.rootPackage
 *
 * Params (v1):
 * - mode: optional enum { AUTO, EXPLICIT } (case-insensitive). Defaults to AUTO.
 * - value: required when mode=EXPLICIT.
 *
 * Behavior:
 * - AUTO: use config.project.rootPackage.value (if present)
 * - EXPLICIT: use params.value
 * - If file has an explicit package and it does not start with expected root => finding.
 * - If file has no package statement => no finding (default package is allowed).
 */
class PackagesRootPackageRule : EngineRule {
    override val id: String = "packages.rootPackage"

    private enum class Mode { AUTO, EXPLICIT }

    override fun evaluate(
        file: PsiFile,
        facts: FactsIndex,
        rule: RuleDef,
        config: ShamashPsiConfigV1,
    ): List<Finding> {
        ProgressManager.checkCanceled()

        val p = Params.of(rule.params, "rules.${rule.type}.${rule.name}.params")
        val mode = p.optionalEnum<Mode>("mode") ?: Mode.AUTO
        val value = p.optionalString("value")?.trim()

        val expectedRoot =
            when (mode) {
                Mode.AUTO ->
                    config.project.rootPackage
                        ?.value
                        ?.trim()
                        .orEmpty()
                Mode.EXPLICIT -> value.orEmpty()
            }.trim().trimEnd('.')

        if (expectedRoot.isBlank()) return emptyList()

        val pkg = extractPackageName(file) ?: return emptyList()

        val ok = pkg == expectedRoot || pkg.startsWith("$expectedRoot.")
        if (ok) return emptyList()

        val path = normalizePath(file.virtualFile?.path ?: file.name)
        val start = packageStatementOffset(file.text)

        val ruleInstanceId = RuleUtil.ruleInstanceId(rule, fallbackEngineRuleId = id)

        return listOf(
            Finding(
                ruleId = ruleInstanceId,
                message = "Package '$pkg' is outside configured root package '$expectedRoot'.",
                filePath = path,
                severity = RuleUtil.severity(rule),
                startOffset = start,
                endOffset = start?.let { it + "package".length }, // highlight keyword area
                data =
                    mapOf(
                        "package" to pkg,
                        "expectedRoot" to expectedRoot,
                        "mode" to mode.name,
                    ),
            ),
        )
    }

    /**
     * Extract package name from file text.
     *
     * We avoid PSI language-specific classes; this works for both Kotlin and Java.
     */
    private fun extractPackageName(file: PsiFile): String? {
        val text = file.text ?: return null

        // Fast scan: iterate lines until we see a package directive or a non-header statement.
        // Header can contain blank lines, comments, and annotations.
        var inBlockComment = false

        for (rawLine in text.lineSequence()) {
            ProgressManager.checkCanceled()

            var line = rawLine
            if (line.endsWith('\r')) line = line.dropLast(1)

            val t = line.trim()
            if (t.isEmpty()) continue

            // Basic block comment skipping (good enough for header parsing)
            if (inBlockComment) {
                if (t.contains("*/")) inBlockComment = false
                continue
            }
            if (t.startsWith("/*")) {
                if (!t.contains("*/")) inBlockComment = true
                continue
            }
            if (t.startsWith("//")) continue
            if (t.startsWith("*")) continue // javadoc interior lines

            // Kotlin/Java package statement
            if (t.startsWith("package ")) {
                val after = t.removePrefix("package ").trim()
                // Java allows trailing semicolon; Kotlin doesn't
                val token =
                    after
                        .trimEnd(';')
                        .split(' ', '\t')
                        .firstOrNull()
                        .orEmpty()
                return token.takeIf { it.isNotBlank() }
            }

            // If we hit imports or declarations without a package statement, stop.
            // Kotlin requires package before imports; Java too. If absent, it's default package.
            if (t.startsWith("import ") ||
                t.startsWith("class ") || t.startsWith("interface ") ||
                t.startsWith("object ") || t.startsWith("enum ") || t.startsWith("fun ") ||
                t.startsWith("public ") || t.startsWith("private ") || t.startsWith("protected ")
            ) {
                break
            }

            // Allow annotations as "header noise" before package in weird cases, keep scanning a bit.
            if (!t.startsWith("@")) break
        }

        return null
    }

    private fun packageStatementOffset(text: String?): Int? {
        if (text.isNullOrEmpty()) return null
        val idx = text.indexOf("package ")
        return if (idx >= 0) idx else null
    }

    private fun normalizePath(path: String): String = path.replace('\\', '/')
}
