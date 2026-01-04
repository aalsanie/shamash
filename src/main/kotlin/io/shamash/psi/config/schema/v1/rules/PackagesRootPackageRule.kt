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
package io.shamash.psi.config.schema.v1.rules

import com.intellij.psi.PsiFile
import io.shamash.psi.config.schema.v1.model.Rule
import io.shamash.psi.config.schema.v1.model.ShamashPsiConfigV1
import io.shamash.psi.engine.EngineRule
import io.shamash.psi.engine.Finding
import io.shamash.psi.engine.FindingSeverity
import io.shamash.psi.facts.model.v1.FactsIndex

/**
 * Rule: packages.rootPackage
 *
 * Ensures that source files belong to a configured root package.
 *
 * Supported params (expected by PackagesRootPackageSpec):
 *  - mode: "explicit" | "auto"  (default: "auto")
 *  - value: "<root.package>"    (required when mode=explicit)
 *
 * Behavior:
 *  - Determines "expected root":
 *      - explicit: rule.params.value
 *      - auto: config.project.rootPackage.value (if present)
 *  - Extracts the file package from the first meaningful "package ..." statement.
 *  - If a package is present and does not start with expected root -> finding.
 *  - If no package statement exists -> no finding (keeps script-ish / default-package files quiet).
 *
 * Notes:
 *  - Works for both Kotlin and Java without Kotlin PSI dependency.
 *  - FactsIndex is currently unused; kept to match EngineRule signature.
 */
class PackagesRootPackageRule : EngineRule {
    override val id: String = "packages.rootPackage"

    override fun evaluate(
        file: PsiFile,
        facts: FactsIndex,
        rule: Rule,
        config: ShamashPsiConfigV1,
    ): List<Finding> {
        if (!rule.enabled) return emptyList()

        val expectedRoot = resolveExpectedRoot(rule, config).trim().trimEnd('.')
        if (expectedRoot.isBlank()) return emptyList()

        val pkg = extractPackageName(file) ?: return emptyList()

        val ok = pkg == expectedRoot || pkg.startsWith("$expectedRoot.")
        if (ok) return emptyList()

        val path = file.virtualFile?.path ?: file.name
        val start = packageStatementOffset(file.text)

        return listOf(
            Finding(
                ruleId = id,
                message = "Package '$pkg' is outside configured root package '$expectedRoot'.",
                filePath = path,
                severity = mapSeverity(rule),
                startOffset = start,
                endOffset = start?.let { it + 7 }, // "package" keyword length; lightweight highlighting
            ),
        )
    }

    private fun resolveExpectedRoot(
        rule: Rule,
        config: ShamashPsiConfigV1,
    ): String {
        // Rule params are modeled/typed in your schema model, so access them directly
        // instead of assuming a generic map.
        //
        // In your schema, Rule has known fields (enabled/severity/scope/params...).
        // If your Rule model uses strongly-typed properties for mode/value, adjust accordingly.
        // This implementation assumes `rule.params` is a Map<String, Any?> (common in your codebase).
        val mode = (rule.params["mode"] as? String)?.trim()?.lowercase() ?: "auto"
        val value = (rule.params["value"] as? String)?.trim().orEmpty()

        return when (mode) {
            "explicit" -> value
            "auto" ->
                config.project.rootPackage.value
                    ?.trim()
                    .orEmpty()
            else ->
                if (value.isNotBlank()) {
                    value
                } else {
                    config.project.rootPackage.value
                        ?.trim()
                        .orEmpty()
                }
        }
    }

    private fun mapSeverity(rule: Rule): FindingSeverity {
        // Your Rule.severity is likely an enum in schema model. We map by name to avoid import coupling.
        return when (rule.severity.name.uppercase()) {
            "ERROR" -> FindingSeverity.ERROR
            "WARNING" -> FindingSeverity.WARNING
            "INFO" -> FindingSeverity.INFO
            else -> FindingSeverity.WARNING
        }
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
            var line = rawLine

            // Handle CRLF remnants if lineSequence() is used on text with '\r\n'
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
            if (t.startsWith("import ") || t.startsWith("class ") || t.startsWith("interface ") ||
                t.startsWith("object ") || t.startsWith("enum ") || t.startsWith("fun ") ||
                t.startsWith("public ") || t.startsWith("private ") || t.startsWith("protected ")
            ) {
                break
            }

            // Allow annotations as "header noise" before package in weird cases, keep scanning a bit
            // but if the file begins with arbitrary code, we stop soon anyway.
            if (!t.startsWith("@")) {
                // unknown statement encountered; stop to avoid scanning entire file
                break
            }
        }

        return null
    }

    private fun packageStatementOffset(text: String?): Int? {
        if (text.isNullOrEmpty()) return null
        val idx = text.indexOf("package ")
        return if (idx >= 0) idx else null
    }
}
