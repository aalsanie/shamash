/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.psi.core.engine.rules

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiFile
import io.shamash.artifacts.contract.Finding
import io.shamash.artifacts.params.ParamError
import io.shamash.artifacts.params.Params
import io.shamash.psi.core.config.schema.v1.model.RuleDef
import io.shamash.psi.core.config.schema.v1.model.ShamashPsiConfigV1
import io.shamash.psi.core.engine.EngineRule
import io.shamash.psi.core.facts.model.v1.FactsIndex

/** AUTO reads the project root; EXPLICIT reads the rule value. Files without a package are allowed. */
class PackagesRootPackageRule : EngineRule {
    override val id: String = "packages.rootPackage"

    private enum class Mode { AUTO, EXPLICIT }

    private val rootPackagePattern = Regex("^[A-Za-z_]\\w*(\\.[A-Za-z_]\\w*)*$")

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
                Mode.AUTO -> {
                    config.project.rootPackage
                        ?.value
                        ?.trim()
                        .orEmpty()
                }

                Mode.EXPLICIT -> {
                    value.orEmpty()
                }
            }.trim()

        if (expectedRoot.isBlank()) {
            throw ParamError("${p.currentPath}.mode", "Root package is not configured for ${mode.name}")
        }
        if (!rootPackagePattern.matches(expectedRoot)) {
            throw ParamError("${p.currentPath}.value", "must be a valid Java/Kotlin package name")
        }
        if (mode == Mode.AUTO && value != null) {
            throw ParamError("${p.currentPath}.value", "must be omitted when mode=AUTO")
        }

        if (RuleUtil.scopedClasses(facts, rule).isEmpty()) return emptyList()

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
                endOffset = start?.let { it + "package".length },
                data =
                    mapOf(
                        "package" to pkg,
                        "expectedRoot" to expectedRoot,
                        "mode" to mode.name,
                    ),
            ),
        )
    }

    /** Read the header as text to support both Kotlin and Java without language-specific PSI. */
    private fun extractPackageName(file: PsiFile): String? {
        val text = file.text ?: return null

        var inBlockComment = false

        for (rawLine in text.lineSequence()) {
            ProgressManager.checkCanceled()

            var line = rawLine
            if (line.endsWith('\r')) line = line.dropLast(1)

            val t = line.trim()
            if (t.isEmpty()) continue

            if (inBlockComment) {
                if (t.contains("*/")) inBlockComment = false
                continue
            }
            if (t.startsWith("/*")) {
                if (!t.contains("*/")) inBlockComment = true
                continue
            }
            if (t.startsWith("//")) continue
            if (t.startsWith("*")) continue

            if (t.startsWith("package ")) {
                val after = t.removePrefix("package ").trim()
                val token =
                    after
                        .trimEnd(';')
                        .split(' ', '\t')
                        .firstOrNull()
                        .orEmpty()
                return token.takeIf { it.isNotBlank() }
            }

            if (t.startsWith("import ") ||
                t.startsWith("class ") || t.startsWith("interface ") ||
                t.startsWith("object ") || t.startsWith("enum ") || t.startsWith("fun ") ||
                t.startsWith("public ") || t.startsWith("private ") || t.startsWith("protected ")
            ) {
                break
            }

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
