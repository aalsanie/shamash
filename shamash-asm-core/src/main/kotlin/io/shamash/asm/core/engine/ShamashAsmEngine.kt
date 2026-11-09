/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.asm.core.engine

import io.shamash.artifacts.baseline.BaselineFingerprint
import io.shamash.artifacts.baseline.BaselineStore
import io.shamash.artifacts.contract.Finding
import io.shamash.artifacts.params.ParamError
import io.shamash.artifacts.report.layout.ExportOutputLayout
import io.shamash.artifacts.report.schema.v1.ExportedReport
import io.shamash.artifacts.util.PathNormalizer
import io.shamash.artifacts.util.glob.GlobMatcher
import io.shamash.asm.core.analysis.AnalysisResult
import io.shamash.asm.core.analysis.AsmAnalysisPipeline
import io.shamash.asm.core.config.ConfigValidation
import io.shamash.asm.core.config.SchemaValidator
import io.shamash.asm.core.config.SchemaValidatorNetworkNt
import io.shamash.asm.core.config.schema.v1.model.BaselineMode
import io.shamash.asm.core.config.schema.v1.model.ExceptionDef
import io.shamash.asm.core.config.schema.v1.model.ExceptionMatch
import io.shamash.asm.core.config.schema.v1.model.ExportFactsFormat
import io.shamash.asm.core.config.schema.v1.model.ExportFormat
import io.shamash.asm.core.config.schema.v1.model.Matcher
import io.shamash.asm.core.config.schema.v1.model.RoleDef
import io.shamash.asm.core.config.schema.v1.model.RuleDef
import io.shamash.asm.core.config.schema.v1.model.RuleKey
import io.shamash.asm.core.config.schema.v1.model.RuleScope
import io.shamash.asm.core.config.schema.v1.model.ShamashAsmConfigV1
import io.shamash.asm.core.config.schema.v1.model.UnknownRulePolicy
import io.shamash.asm.core.engine.rules.ContextAwareRule
import io.shamash.asm.core.engine.rules.DefaultRuleRegistry
import io.shamash.asm.core.engine.rules.Rule
import io.shamash.asm.core.engine.rules.RuleExecutionContext
import io.shamash.asm.core.engine.rules.RuleRegistry
import io.shamash.asm.core.export.analysis.AnalysisExporter
import io.shamash.asm.core.export.facts.FactsExporter
import io.shamash.asm.core.export.roles.RolesExporter
import io.shamash.asm.core.export.rules.RulePlanEntry
import io.shamash.asm.core.export.rules.RulePlanExporter
import io.shamash.asm.core.export.rules.RulePlanStatus
import io.shamash.asm.core.facts.model.ClassFact
import io.shamash.asm.core.facts.query.FactIndex
import io.shamash.export.api.Exporters
import io.shamash.export.pipeline.CoherentReportPublisher
import io.shamash.export.pipeline.ExportOrchestrator
import io.shamash.export.pipeline.ReportBuilder
import java.io.Reader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import kotlin.io.path.name

class ShamashAsmEngine
    @JvmOverloads
    constructor(
        private val registry: RuleRegistry = DefaultRuleRegistry.create(),
        private val toolName: String = "Shamash ASM",
        private val toolVersion: String = ShamashAsmEngine::class.java.`package`.implementationVersion ?: "dev",
    ) {
        @JvmOverloads
        fun validateConfig(
            reader: Reader,
            schemaValidator: SchemaValidator = SchemaValidatorNetworkNt,
        ): ConfigValidation.Result = ConfigValidation.loadAndValidateV1(reader, registry, schemaValidator)

        @JvmOverloads
        fun analyze(
            projectBasePath: Path,
            projectName: String,
            config: ShamashAsmConfigV1,
            facts: FactIndex,
            generatedAtEpochMillis: Long = System.currentTimeMillis(),
            includeFactsInResult: Boolean = false,
        ): EngineResult =
            analyzeWithInputErrors(
                projectBasePath = projectBasePath,
                projectName = projectName,
                config = config,
                facts = facts,
                generatedAtEpochMillis = generatedAtEpochMillis,
                includeFactsInResult = includeFactsInResult,
                inputErrors = emptyList(),
            )

        private class BaselinePublicationFailure(cause: Throwable) :
            IllegalStateException("Baseline publication failed", cause)

        internal fun analyzeWithInputErrors(
            projectBasePath: Path,
            projectName: String,
            config: ShamashAsmConfigV1,
            facts: FactIndex,
            generatedAtEpochMillis: Long = System.currentTimeMillis(),
            includeFactsInResult: Boolean = false,
            inputErrors: List<EngineError>,
        ): EngineResult {
            val startedAt = System.currentTimeMillis()

            val errors = inputErrors.toMutableList()
            val findingsOut = mutableListOf<Finding>()

            val roleClassification =
                try {
                    classifyRoles(facts, config.roles)
                } catch (t: Throwable) {
                    errors += EngineError.internal("Role classification failed", t = t)
                    RoleClassification(emptyMap(), emptyMap())
                }

            val factsWithRoles =
                facts.copy(
                    roles = roleClassification.roles,
                    classToRole = roleClassification.classToRole,
                )

            var configuredRuleDefs = 0
            var executedRuleDefs = 0
            var skippedRuleDefs = 0

            var executedRuleInstances = 0
            var skippedRuleInstances = 0
            var notFoundRuleInstances = 0
            var failedRuleInstances = 0
            val captureRulePlan =
                config.export.enabled && config.export.artifacts
                    ?.rulePlan
                    ?.enabled == true
            val rulePlan = if (captureRulePlan) mutableListOf<RulePlanEntry>() else null

            for (ruleDef in config.rules) {
                configuredRuleDefs++
                val type = ruleDef.type.trim()
                val name = ruleDef.name.trim()
                val baseRuleId = "$type.$name"

                if (!ruleDef.enabled) {
                    skippedRuleDefs++
                    rulePlan?.add(
                        RulePlanEntry(
                            baseRuleId,
                            null,
                            null,
                            RulePlanStatus.DISABLED,
                            ruleDef.severity,
                            ruleDef.scope,
                        ),
                    )
                    continue
                }

                val rule: Rule? = registry.resolve(ruleDef)

                if (rule == null) {
                    notFoundRuleInstances++
                    rulePlan?.add(
                        RulePlanEntry(
                            baseRuleId,
                            null,
                            null,
                            RulePlanStatus.NOT_FOUND,
                            ruleDef.severity,
                            ruleDef.scope,
                        ),
                    )

                    // WARN is reported by config validation; it must not become an engine failure.
                    when (config.project.validation.unknownRule) {
                        UnknownRulePolicy.ERROR, UnknownRulePolicy.error -> {
                            errors += EngineError.ruleNotFound(baseRuleId)
                        }

                        UnknownRulePolicy.WARN, UnknownRulePolicy.warn -> {
                        }

                        UnknownRulePolicy.IGNORE, UnknownRulePolicy.ignore -> {
                        }
                    }

                    skippedRuleDefs++
                    continue
                }

                executedRuleDefs++

                val instanceRoles: List<String?> = expandInstanceRoles(ruleDef.roles)
                if (instanceRoles.isEmpty()) {
                    skippedRuleDefs++
                    rulePlan?.add(
                        RulePlanEntry(
                            baseRuleId,
                            rule.id,
                            null,
                            RulePlanStatus.NO_ROLES,
                            ruleDef.severity,
                            ruleDef.scope,
                        ),
                    )
                    continue
                }

                for (role in instanceRoles) {
                    val roleTrimmed = role?.trim()?.takeIf { it.isNotEmpty() }
                    val instanceRuleId = RuleKey(type, name, roleTrimmed).canonicalId()

                    val excludedByScope =
                        roleTrimmed != null && ruleDef.scope?.excludeRoles?.any { it.trim() == roleTrimmed } == true
                    if (excludedByScope) {
                        skippedRuleInstances++
                        rulePlan?.add(
                            RulePlanEntry(
                                instanceRuleId,
                                rule.id,
                                roleTrimmed,
                                RulePlanStatus.EXCLUDED,
                                ruleDef.severity,
                                ruleDef.scope,
                            ),
                        )
                        continue
                    }

                    val effectiveDef = applyRoleToRuleDef(ruleDef, roleTrimmed)

                    try {
                        executedRuleInstances++

                        val produced: List<Finding> =
                            if (rule is ContextAwareRule) {
                                rule.evaluate(factsWithRoles, effectiveDef, config, RuleExecutionContext(projectBasePath))
                            } else {
                                rule.evaluate(factsWithRoles, effectiveDef, config)
                            }

                        for (f in produced) {
                            findingsOut += normalizeFinding(f, instanceRuleId)
                        }
                        rulePlan?.add(
                            RulePlanEntry(
                                instanceRuleId,
                                rule.id,
                                roleTrimmed,
                                RulePlanStatus.EXECUTED,
                                ruleDef.severity,
                                effectiveDef.scope,
                                produced.size,
                            ),
                        )
                    } catch (pe: ParamError) {
                        rulePlan?.add(
                            RulePlanEntry(
                                instanceRuleId,
                                rule.id,
                                roleTrimmed,
                                RulePlanStatus.FAILED,
                                ruleDef.severity,
                                effectiveDef.scope,
                            ),
                        )
                        failedRuleInstances++
                        errors +=
                            EngineError.ruleExecutionFailed(
                                ruleId = instanceRuleId,
                                message = "Rule param error: ${pe.message}",
                                details = mapOf("baseRuleId" to rule.id, "at" to pe.at),
                                t = pe,
                            )
                    } catch (t: Throwable) {
                        rulePlan?.add(
                            RulePlanEntry(
                                instanceRuleId,
                                rule.id,
                                roleTrimmed,
                                RulePlanStatus.FAILED,
                                ruleDef.severity,
                                effectiveDef.scope,
                            ),
                        )
                        failedRuleInstances++
                        errors +=
                            EngineError.ruleExecutionFailed(
                                ruleId = instanceRuleId,
                                message = "Rule '${rule.id}' crashed",
                                details = mapOf("baseRuleId" to rule.id),
                                t = t,
                            )
                    }
                }
            }

            val afterExceptions =
                try {
                    ExceptionSuppressor.compile(config.exceptions).suppress(findingsOut)
                } catch (t: Throwable) {
                    errors += EngineError.internal("Failed to apply exceptions suppression", t = t)
                    findingsOut.toList()
                }

            val baselinePath = resolveBaselinePath(projectBasePath, config.baseline.path)
            val afterBaseline =
                try {
                    if (config.baseline.mode == BaselineMode.VERIFY) {
                        applyBaseline(
                            projectBasePath = projectBasePath,
                            findings = afterExceptions,
                            baselinePath = baselinePath,
                        )
                    } else {
                        afterExceptions
                    }
                } catch (t: Throwable) {
                    errors +=
                        EngineError.baselineFailed(
                            message = "Baseline handling failed",
                            details = mapOf("path" to baselinePath.toString(), "mode" to config.baseline.mode.name),
                            t = t,
                        )
                    afterExceptions
                }

            val findings = normalizeAndSortFindings(afterBaseline)

            val analysisResult: AnalysisResult? =
                if (!AsmAnalysisPipeline.shouldRun(config.analysis)) {
                    null
                } else {
                    try {
                        AsmAnalysisPipeline.run(factsWithRoles, config.analysis)
                    } catch (t: Throwable) {
                        errors += EngineError.internal("Analysis pipeline failed", t = t)
                        null
                    }
                }

            var exportResult: EngineExportResult? = null
            if (config.export.enabled && errors.isEmpty()) {
                try {
                    val outputDir = resolveExportDir(projectBasePath, config)
                    if (!config.export.overwrite && anyManagedReportExists(outputDir)) {
                        if (config.baseline.mode == BaselineMode.GENERATE) {
                            errors +=
                                EngineError.exportFailed(
                                    message = "Baseline generation requires completed report publication",
                                    details = mapOf("outputDir" to outputDir.toString()),
                                )
                        }
                    } else {
                        val fingerprints =
                            if (config.baseline.mode == BaselineMode.GENERATE) {
                                requireValidBaselineDestination(outputDir, baselinePath)
                                computeFingerprints(projectBasePath, findings)
                            } else {
                                null
                            }
                        val published =
                            publishReports(
                                projectBasePath = projectBasePath,
                                projectName = projectName,
                                config = config,
                                findings = findings,
                                facts = factsWithRoles,
                                rulePlan = rulePlan,
                                analysis = analysisResult,
                                outputDir = outputDir,
                                generatedAtEpochMillis = generatedAtEpochMillis,
                                onActivated = {
                                    if (fingerprints != null) {
                                        try {
                                            writeBaselineFile(baselinePath, fingerprints)
                                        } catch (failure: Throwable) {
                                            throw BaselinePublicationFailure(failure)
                                        }
                                    }
                                },
                            )
                        exportResult = if (fingerprints == null) published else published.copy(baselineWritten = true)
                    }
                } catch (failure: BaselinePublicationFailure) {
                    errors +=
                        EngineError.baselineFailed(
                            message = "Baseline publication failed; report generation was rolled back",
                            details = mapOf("path" to baselinePath.toString()),
                            t = failure.cause ?: failure,
                        )
                    if (failure.suppressed.isNotEmpty()) {
                        errors +=
                            EngineError.exportFailed(
                                message = "Report restoration failed after baseline publication failure",
                                details = mapOf("outputDir" to config.export.outputDir),
                                t = failure,
                            )
                    }
                } catch (failure: Throwable) {
                    errors +=
                        EngineError.exportFailed(
                            message = "Export failed",
                            details = mapOf("outputDir" to config.export.outputDir),
                            t = failure,
                        )
                }
            }

            if (config.baseline.mode == BaselineMode.GENERATE) {
                if (errors.isNotEmpty()) {
                    if (errors.none { it.code == EngineError.Code.BASELINE_FAILED }) {
                        errors +=
                            EngineError.baselineFailed(
                                message = "Baseline generation skipped because the engine reported errors",
                                details = mapOf("path" to baselinePath.toString()),
                            )
                    }
                } else if (!config.export.enabled) {
                    try {
                        writeBaselineFile(baselinePath, computeFingerprints(projectBasePath, findings))
                    } catch (failure: Throwable) {
                        errors +=
                            EngineError.baselineFailed(
                                message = "Baseline generation failed",
                                details = mapOf("path" to baselinePath.toString()),
                                t = failure,
                            )
                    }
                }
            }

            val finishedAt = System.currentTimeMillis()

            val summary =
                EngineRunSummary(
                    projectName = projectName,
                    projectBasePath = projectBasePath,
                    toolName = toolName,
                    toolVersion = toolVersion,
                    startedAtEpochMillis = startedAt,
                    finishedAtEpochMillis = finishedAt,
                    factsStats =
                        EngineRunSummary.FactsStats(
                            classes = facts.classes.size,
                            methods = facts.methods.size,
                            fields = facts.fields.size,
                            edges = facts.edges.size,
                        ),
                    ruleStats =
                        EngineRunSummary.RuleStats(
                            configuredRules = configuredRuleDefs,
                            executedRules = executedRuleDefs,
                            skippedRules = skippedRuleDefs,
                            executedRuleInstances = executedRuleInstances,
                            skippedRuleInstances = skippedRuleInstances,
                            notFoundRuleInstances = notFoundRuleInstances,
                            failedRuleInstances = failedRuleInstances,
                        ),
                )

            val stabilizedErrors = stabilizeErrors(errors)

            return if (stabilizedErrors.isEmpty()) {
                EngineResult.success(
                    summary = summary,
                    findings = findings,
                    analysis = analysisResult,
                    export = exportResult,
                    facts = if (includeFactsInResult) factsWithRoles else null,
                )
            } else {
                EngineResult.failed(
                    summary = summary,
                    errors = stabilizedErrors,
                    findings = findings,
                    analysis = analysisResult,
                    export = exportResult,
                    facts = if (includeFactsInResult) factsWithRoles else null,
                )
            }
        }

        private fun expandInstanceRoles(roles: List<String>?): List<String?> {
            if (roles == null) return listOf(null)
            return roles
                .asSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .toList()
        }

        private fun applyRoleToRuleDef(
            ruleDef: RuleDef,
            role: String?,
        ): RuleDef {
            if (role == null) return ruleDef

            // Ensure the instance RuleDef is explicitly scoped to this role without clobbering user scope.
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
                    if (scope0.includeRoles == null) {
                        scope0.copy(includeRoles = listOf(role))
                    } else {
                        scope0
                    }
                }

            return ruleDef.copy(roles = listOf(role), scope = scope)
        }

        private fun normalizeFinding(
            f: Finding,
            canonicalRuleId: String,
        ): Finding {
            // Normalize paths consistently for exceptions, baselines, and cross-platform reports.
            val fixedPath =
                if (f.filePath.isBlank()) {
                    // Stable fallback: prefer classFqn (or empty) rather than "" which makes baseline path relativization ambiguous.
                    (f.classFqn ?: "").let { GlobMatcher.normalizePath(it) }
                } else {
                    GlobMatcher.normalizePath(f.filePath)
                }

            return if (f.ruleId == canonicalRuleId && f.filePath == fixedPath) {
                f
            } else {
                f.copy(ruleId = canonicalRuleId, filePath = fixedPath)
            }
        }

        private fun normalizeAndSortFindings(findings: List<Finding>): List<Finding> {
            if (findings.isEmpty()) return findings

            val unique = findings.distinctBy { findingKey(it) }

            return unique.sortedWith(
                compareBy<Finding>(
                    { it.severity.ordinal },
                    { it.filePath },
                    { it.classFqn ?: "" },
                    { it.memberName ?: "" },
                    { it.ruleId },
                    { it.message },
                ),
            )
        }

        private fun findingKey(f: Finding): String =
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
                append(f.startOffset?.toString() ?: "")
                append('|')
                append(f.endOffset?.toString() ?: "")
                append('|')
                append(
                    f.data.entries
                        .sortedBy { it.key }
                        .joinToString("&") { "${it.key}=${it.value}" },
                )
                append('|')
                append(f.message)
            }

        private fun stabilizeErrors(errors: List<EngineError>): List<EngineError> {
            if (errors.isEmpty()) return errors
            val unique =
                errors.distinctBy {
                    buildString(256) {
                        append(it.code.name)
                        append('|')
                        append(it.message)
                        append('|')
                        append(
                            it.details.entries
                                .sortedBy { e -> e.key }
                                .joinToString(",") { e -> "${e.key}=${e.value}" },
                        )
                        append('|')
                        append(it.cause?.type ?: "")
                    }
                }

            return unique.sortedWith(compareBy({ it.code.name }, { it.message }))
        }

        private fun applyBaseline(
            projectBasePath: Path,
            findings: List<Finding>,
            baselinePath: Path,
        ): List<Finding> {
            val baselineFingerprints: Set<String> = loadBaselineFile(baselinePath)
            if (baselineFingerprints.isEmpty()) return findings

            val out = ArrayList<Finding>(findings.size)
            for (f in findings) {
                val targetPath =
                    if (f.filePath.isBlank()) {
                        Paths.get(f.classFqn ?: "")
                    } else {
                        Paths.get(f.filePath)
                    }

                val normalizedPath =
                    PathNormalizer.relativizeOrNormalize(
                        base = projectBasePath,
                        target = targetPath,
                    )

                val fp = BaselineFingerprint.sha256Hex(f, normalizedPath)
                if (fp !in baselineFingerprints) out += f
            }
            return out
        }

        private fun computeFingerprints(
            projectBasePath: Path,
            findings: List<Finding>,
        ): Set<String> {
            if (findings.isEmpty()) return emptySet()
            val out = LinkedHashSet<String>(findings.size)
            for (f in findings) {
                val targetPath =
                    if (f.filePath.isBlank()) {
                        Paths.get(f.classFqn ?: "")
                    } else {
                        Paths.get(f.filePath)
                    }

                val normalizedPath =
                    PathNormalizer.relativizeOrNormalize(
                        base = projectBasePath,
                        target = targetPath,
                    )

                out += BaselineFingerprint.sha256Hex(f, normalizedPath)
            }
            return out
        }

        private fun loadBaselineFile(path: Path): Set<String> = BaselineStore().loadFile(path)?.fingerprints.orEmpty()

        private fun writeBaselineFile(
            path: Path,
            fingerprints: Set<String>,
        ) {
            Files.createDirectories(path.parent)
            val json = BaselineStore().serialize(fingerprints)

            val tmp = Files.createTempFile(path.parent, ".${path.name}.", ".tmp")
            try {
                Files.writeString(tmp, json, StandardCharsets.UTF_8)
                // Keep the previous baseline intact if the filesystem cannot replace it atomically.
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } finally {
                Files.deleteIfExists(tmp)
            }
        }

        private fun requireValidBaselineDestination(
            outputDir: Path,
            baselinePath: Path,
        ) {
            val baseline = baselinePath.toAbsolutePath().normalize()
            require(!outputDir.startsWith(baseline)) {
                "Baseline destination cannot be the report directory or an ancestor"
            }
            require(
                baseline.parent != outputDir ||
                    baseline.fileName.toString() !in ExportOutputLayout.MANAGED_REPORT_FILE_NAMES,
            ) {
                "Baseline destination conflicts with a managed report"
            }
        }

        private fun resolveBaselinePath(
            projectBasePath: Path,
            raw: String,
        ): Path {
            val p = resolvePath(projectBasePath, raw)
            val s = p.toString()
            return if (s.endsWith(".json")) p else p.resolve("baseline.json").normalize()
        }

        private fun publishReports(
            projectBasePath: Path,
            projectName: String,
            config: ShamashAsmConfigV1,
            findings: List<Finding>,
            facts: FactIndex,
            rulePlan: List<RulePlanEntry>?,
            analysis: AnalysisResult?,
            outputDir: Path,
            generatedAtEpochMillis: Long,
            onActivated: () -> Unit,
        ): EngineExportResult =
            CoherentReportPublisher().publish(outputDir, onActivated) { staging ->
                val report =
                    exportReport(
                        projectBasePath = projectBasePath,
                        projectName = projectName,
                        outputDir = staging,
                        formats = config.export.formats,
                        findings = findings,
                        generatedAtEpochMillis = generatedAtEpochMillis,
                    )
                val files = resolveExportSidecarPaths(staging, config)
                files.factsPath?.let {
                    FactsExporter.export(
                        facts = facts,
                        outputPath = it,
                        format =
                            config.export.artifacts
                                ?.facts
                                ?.format ?: ExportFactsFormat.JSONL_GZ,
                        toolName = toolName,
                        toolVersion = toolVersion,
                        projectName = projectName,
                        generatedAtEpochMillis = generatedAtEpochMillis,
                    )
                }
                files.rolesPath?.let {
                    RolesExporter.export(
                        facts = facts,
                        roleDefs = config.roles,
                        outputPath = it,
                        toolName = toolName,
                        toolVersion = toolVersion,
                        projectName = projectName,
                        generatedAtEpochMillis = generatedAtEpochMillis,
                    )
                }
                files.rulePlanPath?.let {
                    RulePlanExporter.export(
                        entries = requireNotNull(rulePlan) { "Rule plan was requested but not computed" },
                        outputPath = it,
                        toolName = toolName,
                        toolVersion = toolVersion,
                        projectName = projectName,
                        generatedAtEpochMillis = generatedAtEpochMillis,
                    )
                }
                files.analysisGraphsPath?.let {
                    AnalysisExporter.exportGraphs(
                        graphs = requireNotNull(analysis?.graphs) { "Required analysis graphs are unavailable" },
                        outputPath = it,
                        toolName = toolName,
                        toolVersion = toolVersion,
                        projectName = projectName,
                        generatedAtEpochMillis = generatedAtEpochMillis,
                    )
                }
                files.analysisHotspotsPath?.let {
                    AnalysisExporter.exportHotspots(
                        hotspots = requireNotNull(analysis?.hotspots) { "Required analysis hotspots are unavailable" },
                        outputPath = it,
                        toolName = toolName,
                        toolVersion = toolVersion,
                        projectName = projectName,
                        generatedAtEpochMillis = generatedAtEpochMillis,
                    )
                }
                files.analysisScoresPath?.let {
                    AnalysisExporter.exportScores(
                        scoring = requireNotNull(analysis?.scoring) { "Required analysis scores are unavailable" },
                        outputPath = it,
                        toolName = toolName,
                        toolVersion = toolVersion,
                        projectName = projectName,
                        generatedAtEpochMillis = generatedAtEpochMillis,
                    )
                }

                fun published(path: Path?): Path? = path?.let { outputDir.resolve(it.fileName) }
                EngineExportResult(
                    report = report,
                    outputDir = outputDir,
                    baselineWritten = false,
                    factsPath = published(files.factsPath),
                    rolesPath = published(files.rolesPath),
                    rulePlanPath = published(files.rulePlanPath),
                    analysisGraphsPath = published(files.analysisGraphsPath),
                    analysisHotspotsPath = published(files.analysisHotspotsPath),
                    analysisScoresPath = published(files.analysisScoresPath),
                )
            }

        private fun exportReport(
            projectBasePath: Path,
            projectName: String,
            outputDir: Path,
            formats: List<ExportFormat>,
            findings: List<Finding>,
            generatedAtEpochMillis: Long,
        ): ExportedReport {
            val exporterFormats = formats.mapNotNull { it.toExporterFormat() }.toSet()
            val exporters = Exporters.create(exporterFormats)
            val orchestrator = ExportOrchestrator(reportBuilder = ReportBuilder(), exporters = exporters)

            return orchestrator.export(
                projectBasePath = projectBasePath,
                projectName = projectName,
                toolName = toolName,
                toolVersion = toolVersion,
                findings = findings,
                outputDir = outputDir,
                generatedAtEpochMillis = generatedAtEpochMillis,
            )
        }

        private fun anyManagedReportExists(outputDir: Path): Boolean =
            ExportOutputLayout.MANAGED_REPORT_FILE_NAMES.any { Files.exists(outputDir.resolve(it)) }

        private data class ExportSidecarPaths(
            val factsPath: Path?,
            val rolesPath: Path?,
            val rulePlanPath: Path?,
            val analysisGraphsPath: Path?,
            val analysisHotspotsPath: Path?,
            val analysisScoresPath: Path?,
        )

        private fun resolveExportSidecarPaths(
            outputDir: Path,
            config: ShamashAsmConfigV1,
        ): ExportSidecarPaths {
            val artifacts = config.export.artifacts

            val factsPath: Path? =
                artifacts?.facts?.takeIf { it.enabled }?.let { facts ->
                    val fileName =
                        when (facts.format) {
                            ExportFactsFormat.JSONL_GZ -> ExportOutputLayout.FACTS_JSONL_GZ_FILE_NAME
                            ExportFactsFormat.JSON -> ExportOutputLayout.FACTS_JSON_FILE_NAME
                        }
                    ExportOutputLayout.resolve(outputDir, fileName)
                }

            val rolesPath: Path? =
                artifacts?.roles?.takeIf { it.enabled }?.let {
                    ExportOutputLayout.resolve(outputDir, ExportOutputLayout.ROLES_JSON_FILE_NAME)
                }

            val rulePlanPath: Path? =
                artifacts?.rulePlan?.takeIf { it.enabled }?.let {
                    ExportOutputLayout.resolve(outputDir, ExportOutputLayout.RULE_PLAN_JSON_FILE_NAME)
                }

            val analysisGraphsPath: Path? =
                artifacts?.analysis?.takeIf { it.enabled && it.graphs }?.let {
                    ExportOutputLayout.resolve(outputDir, ExportOutputLayout.ANALYSIS_GRAPHS_JSON_FILE_NAME)
                }

            val analysisHotspotsPath: Path? =
                artifacts?.analysis?.takeIf { it.enabled && it.hotspots }?.let {
                    ExportOutputLayout.resolve(outputDir, ExportOutputLayout.ANALYSIS_HOTSPOTS_JSON_FILE_NAME)
                }

            val analysisScoresPath: Path? =
                artifacts?.analysis?.takeIf { it.enabled && it.scoring }?.let {
                    ExportOutputLayout.resolve(outputDir, ExportOutputLayout.ANALYSIS_SCORES_JSON_FILE_NAME)
                }

            return ExportSidecarPaths(
                factsPath = factsPath,
                rolesPath = rolesPath,
                rulePlanPath = rulePlanPath,
                analysisGraphsPath = analysisGraphsPath,
                analysisHotspotsPath = analysisHotspotsPath,
                analysisScoresPath = analysisScoresPath,
            )
        }

        private fun resolveExportDir(
            projectBasePath: Path,
            config: ShamashAsmConfigV1,
        ): Path {
            val raw = config.export.outputDir.trim()
            val chosen =
                if (raw.isEmpty()) {
                    projectBasePath.resolve(ExportOutputLayout.DEFAULT_DIR_NAME)
                } else {
                    resolvePath(projectBasePath, raw)
                }
            val destination = ExportOutputLayout.normalizeOutputDir(projectBasePath, chosen).toAbsolutePath().normalize()
            val project = projectBasePath.toAbsolutePath().normalize()
            require(!project.startsWith(destination)) {
                "Report output cannot replace the project directory or an ancestor"
            }
            return destination
        }

        private fun ExportFormat.toExporterFormat(): Exporters.Format? =
            when (this) {
                ExportFormat.JSON -> Exporters.Format.JSON
                ExportFormat.SARIF -> Exporters.Format.SARIF
                ExportFormat.XML -> Exporters.Format.XML
                ExportFormat.HTML -> Exporters.Format.HTML
            }

        private fun resolvePath(
            projectBasePath: Path,
            raw: String,
        ): Path {
            val p = Paths.get(raw)
            return (if (p.isAbsolute) p else projectBasePath.resolve(p)).normalize()
        }

        private data class RoleClassification(
            val roles: Map<String, Set<String>>,
            val classToRole: Map<String, String>,
        )

        private fun classifyRoles(
            facts: FactIndex,
            roles: Map<String, RoleDef>,
        ): RoleClassification {
            if (roles.isEmpty() || facts.classes.isEmpty()) {
                return RoleClassification(emptyMap(), emptyMap())
            }

            val compiled =
                roles.entries
                    .sortedWith(
                        compareByDescending<Map.Entry<String, RoleDef>> { it.value.priority }
                            .thenBy { it.key },
                    ).map { (roleId, def) -> roleId to compileMatcher(def.match) }

            val roleToClasses = LinkedHashMap<String, MutableSet<String>>(compiled.size)
            val classToRole = LinkedHashMap<String, String>(facts.classes.size)

            for ((roleId, _) in compiled) roleToClasses.putIfAbsent(roleId, linkedSetOf())

            for (c in facts.classes.asSequence().sortedBy { it.fqName }) {
                val winner = compiled.firstOrNull { (_, matcher) -> matcher.matches(c) } ?: continue
                val roleId = winner.first
                roleToClasses.getOrPut(roleId) { linkedSetOf() }.add(c.fqName)
                classToRole[c.fqName] = roleId
            }

            return RoleClassification(
                roles = roleToClasses.mapValues { it.value.toSet() },
                classToRole = classToRole,
            )
        }

        private sealed interface CompiledMatcher {
            fun matches(c: ClassFact): Boolean

            data class AnyOf(
                val anyOf: List<CompiledMatcher>,
            ) : CompiledMatcher {
                override fun matches(c: ClassFact) = anyOf.any { it.matches(c) }
            }

            data class AllOf(
                val allOf: List<CompiledMatcher>,
            ) : CompiledMatcher {
                override fun matches(c: ClassFact) = allOf.all { it.matches(c) }
            }

            data class Not(
                val not: CompiledMatcher,
            ) : CompiledMatcher {
                override fun matches(c: ClassFact) = !not.matches(c)
            }

            data class PackageRegex(
                val regex: Regex,
            ) : CompiledMatcher {
                override fun matches(c: ClassFact) = regex.containsMatchIn(c.packageName)
            }

            data class PackageContainsSegment(
                val segment: String,
            ) : CompiledMatcher {
                override fun matches(c: ClassFact) = c.packageName.split('.').any { it == segment }
            }

            data class ClassNameEndsWith(
                val suffix: String,
            ) : CompiledMatcher {
                override fun matches(c: ClassFact) = c.simpleName.endsWith(suffix)
            }

            data class Annotation(
                val fqn: String,
            ) : CompiledMatcher {
                override fun matches(c: ClassFact) = fqn in c.annotationsFqns
            }

            data class AnnotationPrefix(
                val prefix: String,
            ) : CompiledMatcher {
                override fun matches(c: ClassFact) = c.annotationsFqns.any { it.startsWith(prefix) }
            }
        }

        private fun compileMatcher(m: Matcher): CompiledMatcher =
            when (m) {
                is Matcher.AnyOf -> CompiledMatcher.AnyOf(m.anyOf.map { compileMatcher(it) })
                is Matcher.AllOf -> CompiledMatcher.AllOf(m.allOf.map { compileMatcher(it) })
                is Matcher.Not -> CompiledMatcher.Not(compileMatcher(m.not))
                is Matcher.PackageRegex -> CompiledMatcher.PackageRegex(Regex(m.packageRegex))
                is Matcher.PackageContainsSegment -> CompiledMatcher.PackageContainsSegment(m.packageContainsSegment)
                is Matcher.ClassNameEndsWith -> CompiledMatcher.ClassNameEndsWith(m.classNameEndsWith)
                is Matcher.Annotation -> CompiledMatcher.Annotation(m.annotation)
                is Matcher.AnnotationPrefix -> CompiledMatcher.AnnotationPrefix(m.annotationPrefix)
            }

        private class ExceptionSuppressor private constructor(
            private val compiled: List<CompiledException>,
        ) {
            fun suppress(findings: List<Finding>): List<Finding> {
                if (compiled.isEmpty() || findings.isEmpty()) return findings
                val out = ArrayList<Finding>(findings.size)
                for (f in findings) {
                    val suppressed = compiled.any { it.matches(f) }
                    if (!suppressed) out += f
                }
                return out
            }

            data class CompiledException(
                val ruleId: String?,
                val ruleType: String?,
                val ruleName: String?,
                val roles: Set<String>?,
                val classInternalName: String?,
                val classNameRegex: Regex?,
                val packageRegex: Regex?,
                val originPathRegex: Regex?,
                val glob: String?,
            ) {
                fun matches(f: Finding): Boolean {
                    if (!ruleId.isNullOrBlank()) {
                        if (f.ruleId != ruleId) return false
                    } else {
                        if (!ruleType.isNullOrBlank() && !ruleName.isNullOrBlank()) {
                            val prefix = "${ruleType.trim()}.${ruleName.trim()}"
                            if (!f.ruleId.startsWith(prefix)) return false
                        } else if (!ruleType.isNullOrBlank()) {
                            if (!f.ruleId.startsWith(ruleType.trim() + ".")) return false
                        }
                    }

                    if (roles != null) {
                        val role = extractRoleFromRuleId(f.ruleId)
                        if (role == null || role !in roles) return false
                    }

                    if (!classInternalName.isNullOrBlank()) {
                        val internal = f.data["classInternalName"] ?: return false
                        if (internal != classInternalName.trim()) return false
                    }

                    if (classNameRegex != null) {
                        val cls = f.classFqn ?: return false
                        if (!classNameRegex.containsMatchIn(cls)) return false
                    }

                    if (packageRegex != null) {
                        val cls = f.classFqn ?: return false
                        val pkg = cls.substringBeforeLast('.', missingDelimiterValue = "")
                        if (!packageRegex.containsMatchIn(pkg)) return false
                    }

                    if (originPathRegex != null) {
                        val origin = f.data["originPath"] ?: f.filePath
                        if (!originPathRegex.containsMatchIn(origin)) return false
                    }

                    if (!glob.isNullOrBlank()) {
                        if (!GlobMatcher.matches(glob.trim(), f.filePath)) return false
                    }

                    return true
                }
            }

            companion object {
                fun compile(exceptions: List<ExceptionDef>): ExceptionSuppressor {
                    val enabled = exceptions.asSequence().filter { it.enabled }.toList()
                    if (enabled.isEmpty()) return ExceptionSuppressor(emptyList())

                    val compiled =
                        enabled.map { ex ->
                            val m: ExceptionMatch = ex.match
                            CompiledException(
                                ruleId = m.ruleId?.trim()?.takeIf { it.isNotEmpty() },
                                ruleType = m.ruleType?.trim()?.takeIf { it.isNotEmpty() },
                                ruleName = m.ruleName?.trim()?.takeIf { it.isNotEmpty() },
                                roles = m.roles?.mapNotNull { it.trim().takeIf(String::isNotEmpty) }?.toSet(),
                                classInternalName = m.classInternalName?.trim()?.takeIf { it.isNotEmpty() },
                                classNameRegex =
                                    m.classNameRegex
                                        ?.trim()
                                        ?.takeIf { it.isNotEmpty() }
                                        ?.let { Regex(it) },
                                packageRegex =
                                    m.packageRegex
                                        ?.trim()
                                        ?.takeIf { it.isNotEmpty() }
                                        ?.let { Regex(it) },
                                originPathRegex =
                                    m.originPathRegex
                                        ?.trim()
                                        ?.takeIf { it.isNotEmpty() }
                                        ?.let { Regex(it) },
                                glob = m.glob?.trim()?.takeIf { it.isNotEmpty() },
                            )
                        }

                    return ExceptionSuppressor(compiled)
                }

                private fun extractRoleFromRuleId(ruleId: String): String? {
                    val parts = ruleId.split('.')
                    return if (parts.size >= 3) parts.last().takeIf { it.isNotBlank() } else null
                }
            }
        }
    }
