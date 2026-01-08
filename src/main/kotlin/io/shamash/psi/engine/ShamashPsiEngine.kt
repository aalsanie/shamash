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
package io.shamash.psi.engine

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiFile
import io.shamash.psi.config.schema.v1.model.RuleKey
import io.shamash.psi.config.schema.v1.model.RuleScope
import io.shamash.psi.config.schema.v1.model.ShamashPsiConfigV1
import io.shamash.psi.config.validation.v1.params.ParamError
import io.shamash.psi.engine.index.ProjectRoleIndex
import io.shamash.psi.engine.index.ProjectRoleIndexSnapshot
import io.shamash.psi.engine.registry.RuleRegistry
import io.shamash.psi.facts.FactExtractor
import io.shamash.psi.facts.FactsResult
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

    /**
     * Back-compat API: returns findings only.
     * Prefer [analyzeFileResult] for production usage.
     */
    fun analyzeFile(
        file: PsiFile,
        config: ShamashPsiConfigV1,
    ): List<Finding> = analyzeFileResult(file, config).findings

    /**
     * Production API: findings + structured engine errors.
     */
    fun analyzeFileResult(
        file: PsiFile,
        config: ShamashPsiConfigV1,
    ): EngineResult {
        ProgressManager.checkCanceled()

        val filePath = normalizeFilePath(file)
        val fileId = filePath

        val errors = mutableListOf<EngineError>()

        fun record(
            phase: String,
            t: Throwable,
            ruleId: String? = null,
            message: String? = null,
        ) {
            errors +=
                EngineError(
                    fileId = fileId,
                    phase = phase,
                    message = message ?: (t.message ?: "Engine error"),
                    throwableClass = t::class.java.name,
                    ruleId = ruleId,
                )
        }

        val factsResult: FactsResult =
            try {
                FactExtractor.extractResult(file)
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (t: Throwable) {
                // FactExtractor is already best-effort, but keep engine safe in case of unexpected throw.
                record("facts:extractResult", t)
                FactsResult(
                    facts =
                        io.shamash.psi.facts.model.v1.FactsIndex(
                            classes = emptyList(),
                            methods = emptyList(),
                            fields = emptyList(),
                            dependencies = emptyList(),
                            roles = emptyMap(),
                            classToRole = emptyMap(),
                        ),
                    errors = emptyList(),
                )
            }

        // Propagate facts extraction errors into engine error channel.
        for (e in factsResult.errors) {
            errors +=
                EngineError(
                    fileId = fileId,
                    phase = "facts:${e.phase}",
                    message = e.message,
                    throwableClass = e.throwableClass,
                    ruleId = null,
                )
        }

        val roleIndex: ProjectRoleIndexSnapshot =
            try {
                ProjectRoleIndex.getInstance(file.project).getOrBuild(config)
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (t: Throwable) {
                record("roleIndex:getOrBuild", t)
                emptyRoleIndex()
            }

        val facts =
            factsResult.facts.copy(
                roles = roleIndex.roleToClasses,
                classToRole = roleIndex.classToRole,
            )

        val rawFindings = mutableListOf<Finding>()

        for (ruleDef in config.rules) {
            ProgressManager.checkCanceled()
            if (!ruleDef.enabled) continue

            // Expand authored RuleDef into concrete rule instances:
            // - roles == null => wildcard instance (type.name)
            // - roles != null => one instance per role (type.name.role)
            val roles = ruleDef.roles
            val instanceRoles: List<String?> =
                if (roles == null) {
                    listOf(null)
                } else {
                    roles
                        .asSequence()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .distinct()
                        .map { it }
                        .toList()
                }

            if (instanceRoles.isEmpty()) continue

            val baseId = "${ruleDef.type}.${ruleDef.name}"
            val rule = RuleRegistry.find(baseId)
            if (rule == null) {
                // Validation layer controls unknown-rule policy; engine stays safe.
                log.debug("Unknown rule '$baseId' (enabled=${ruleDef.enabled})")
                continue
            }

            for (role in instanceRoles) {
                ProgressManager.checkCanceled()

                // If a concrete role instance contradicts the authored scope, skip.
                if (role != null && ruleDef.scope?.excludeRoles?.contains(role) == true) continue

                val effectiveRule =
                    if (role == null) {
                        ruleDef
                    } else {
                        val scope0 = ruleDef.scope
                        val scope =
                            if (scope0 == null) {
                                RuleScope(
                                    includeRoles = listOf(role),
                                    excludeRoles = null,
                                    includePackages = null,
                                    excludePackages = null,
                                    includeGlobs = null,
                                    excludeGlobs = null,
                                )
                            } else {
                                // A role-specific rule instance must only apply to that role.
                                // If authored includeRoles exists, we still override it to match the instance.
                                scope0.copy(includeRoles = listOf(role))
                            }

                        ruleDef.copy(scope = scope)
                    }

                val instanceId = RuleKey(ruleDef.type, ruleDef.name, role).canonicalId()

                val produced =
                    try {
                        rule.evaluate(file, facts, effectiveRule, config)
                    } catch (e: ProcessCanceledException) {
                        throw e
                    } catch (e: ParamError) {
                        // Param errors must be explicit and must not crash the scan.
                        errors +=
                            EngineError(
                                fileId = fileId,
                                phase = "rule:params",
                                message = e.message,
                                throwableClass = e::class.java.name,
                                ruleId = instanceId,
                            )
                        emptyList()
                    } catch (t: Throwable) {
                        record("rule:crash", t, ruleId = instanceId, message = "Rule '${rule.id}' crashed")
                        emptyList()
                    }

                // Normalize findings defensively:
                // - ensure filePath
                // - force canonical (expanded) ruleId
                for (f in produced) {
                    val fixedPath = if (f.filePath.isBlank()) filePath else f.filePath
                    rawFindings +=
                        if (f.ruleId == instanceId && f.filePath == fixedPath) {
                            f
                        } else {
                            f.copy(ruleId = instanceId, filePath = fixedPath)
                        }
                }
            }
        }

        val suppressed =
            try {
                ExceptionSuppressor.apply(rawFindings, config, roleIndex, file)
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (t: Throwable) {
                record("suppress:exceptions", t)
                rawFindings
            }

        val inlineSuppressed =
            try {
                InlineSuppressor.apply(suppressed, file)
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (t: Throwable) {
                record("suppress:inline", t)
                suppressed
            }

        val findings = normalizeAndSort(inlineSuppressed)
        val stabilizedErrors = stabilizeErrors(errors)

        return EngineResult(findings = findings, errors = stabilizedErrors)
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

        // Deduplicate and stable sort exports and UI presentation.
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

    private fun stabilizeErrors(errors: List<EngineError>): List<EngineError> {
        if (errors.isEmpty()) return errors
        val unique = errors.distinctBy { "${it.fileId}|${it.phase}|${it.ruleId ?: ""}|${it.message}|${it.throwableClass ?: ""}" }
        return unique.sortedWith(
            compareBy<EngineError>(
                { it.fileId },
                { it.phase },
                { it.ruleId ?: "" },
                { it.message },
                { it.throwableClass ?: "" },
            ),
        )
    }
}
