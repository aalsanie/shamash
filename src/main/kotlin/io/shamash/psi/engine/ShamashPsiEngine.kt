package io.shamash.psi.engine

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiFile
import io.shamash.psi.config.schema.v1.model.ShamashPsiConfigV1
import io.shamash.psi.engine.index.ProjectRoleIndex
import io.shamash.psi.engine.index.ProjectRoleIndexSnapshot
import io.shamash.psi.engine.registry.RuleRegistry
import io.shamash.psi.facts.FactExtractor
import io.shamash.psi.util.GlobMatcher

/**
 * Shamash engine entrypoint.
 *
 * - Use a project-wide role index so dependencies and role matching are not file-local.
 * - Keeps per-file fact extraction cached by modification stamp.
 * - Never crashes the IDE: cancellation-aware and exception-safe per rule.
 * - Deterministic output ordering (stable across runs).
 */
class ShamashPsiEngine {
    private val log: Logger = Logger.getInstance(ShamashPsiEngine::class.java)

    fun analyzeFile(
        file: PsiFile,
        config: ShamashPsiConfigV1,
    ): List<Finding> {
        ProgressManager.checkCanceled()

        val filePath = normalizeFilePath(file)

        val facts0 =
            try {
                FactExtractor.extract(file)
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (t: Throwable) {
                log.warn("Fact extraction failed for $filePath", t)
                return emptyList()
            }

        val roleIndex =
            try {
                ProjectRoleIndex.getInstance(file.project).getOrBuild(config)
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (t: Throwable) {
                log.warn("Project role index build failed; falling back to file-local roles for $filePath", t)
                emptyRoleIndex()
            }

        val facts =
            facts0.copy(
                roles = roleIndex.roleToClasses,
                classToRole = roleIndex.classToRole,
            )

        val rawFindings = mutableListOf<Finding>()

        for ((ruleId, ruleDef) in config.rules) {
            ProgressManager.checkCanceled()
            if (!ruleDef.enabled) continue

            val rule = RuleRegistry.find(ruleId)
            if (rule == null) {
                // configValidator should handle unknownRuleId policy, but engine must be safe too.
                log.debug("Unknown ruleId '$ruleId' (enabled=${ruleDef.enabled})")
                continue
            }

            val produced =
                try {
                    rule.evaluate(file, facts, ruleDef, config)
                } catch (e: ProcessCanceledException) {
                    throw e
                } catch (t: Throwable) {
                    log.warn("Rule '${rule.id}' crashed on $filePath; continuing with remaining rules.", t)
                    emptyList()
                }

            // normalize findings defensively
            // rules may accidentally emit blank filePath.
            for (f in produced) {
                rawFindings +=
                    if (f.filePath.isBlank()) {
                        f.copy(filePath = filePath)
                    } else {
                        f
                    }
            }
        }

        val suppressed =
            try {
                ExceptionSuppressor.apply(rawFindings, config, roleIndex, file)
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (t: Throwable) {
                log.warn("Exception suppression failed for $filePath; returning unsuppressed findings.", t)
                rawFindings
            }

        return normalizeAndSort(suppressed)
    }

    private fun normalizeFilePath(file: PsiFile): String {
        val raw = file.virtualFile?.path ?: file.name
        return GlobMatcher.normalizePath(raw)
    }

    private fun emptyRoleIndex(): ProjectRoleIndexSnapshot =
        ProjectRoleIndexSnapshot(
            roleToClasses = emptyMap(),
            classToRole = emptyMap(),
            classToAnnotations = emptyMap(),
            classToFilePath = emptyMap(),
        )

    private fun normalizeAndSort(findings: List<Finding>): List<Finding> {
        if (findings.isEmpty()) return findings

        // Deduplicate and stable sort
        // exports and UI presentation.
        val unique = findings.distinctBy { key(it) }

        return unique.sortedWith(
            compareBy<Finding>(
                { severityRank(it.severity) },
                { it.filePath },
                { it.classFqn ?: "" },
                { it.memberName ?: "" },
                { it.ruleId },
                { it.message },
            ),
        )
    }

    private fun key(f: Finding): String =
        buildString(256) {
            append(f.ruleId)
            append('|')
            append(f.severity.name)
            append('|')
            append(f.filePath)
            append('|')
            append(f.classFqn ?: "")
            append('|')
            append(f.memberName ?: "")
            append('|')
            append(f.message)
        }

    private fun severityRank(severity: FindingSeverity): Int =
        when (severity) {
            FindingSeverity.ERROR -> 0
            FindingSeverity.WARNING -> 1
            FindingSeverity.INFO -> 2
        }
}
