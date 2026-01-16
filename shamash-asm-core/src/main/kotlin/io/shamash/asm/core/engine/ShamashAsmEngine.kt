/*
 * Copyright © 2025-2026 | Shamash
 *
 * Shamash is a JVM architecture enforcement tool that helps teams
 * define, validate, and continuously enforce architectural boundaries.
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
package io.shamash.asm.core.engine

import io.shamash.artifacts.baseline.BaselineFingerprint
import io.shamash.artifacts.contract.Finding
import io.shamash.artifacts.params.ParamError
import io.shamash.artifacts.report.layout.ExportOutputLayout
import io.shamash.artifacts.report.schema.v1.ExportedReport
import io.shamash.artifacts.util.PathNormalizer
import io.shamash.artifacts.util.glob.GlobMatcher
import io.shamash.asm.core.config.schema.v1.model.BaselineMode
import io.shamash.asm.core.config.schema.v1.model.ExceptionDef
import io.shamash.asm.core.config.schema.v1.model.ExceptionMatch
import io.shamash.asm.core.config.schema.v1.model.ExportFormat
import io.shamash.asm.core.config.schema.v1.model.Matcher
import io.shamash.asm.core.config.schema.v1.model.RoleDef
import io.shamash.asm.core.config.schema.v1.model.RuleDef
import io.shamash.asm.core.config.schema.v1.model.RuleKey
import io.shamash.asm.core.config.schema.v1.model.RuleScope
import io.shamash.asm.core.config.schema.v1.model.ShamashAsmConfigV1
import io.shamash.asm.core.config.schema.v1.model.UnknownRulePolicy
import io.shamash.asm.core.engine.rules.DefaultRuleRegistry
import io.shamash.asm.core.engine.rules.Rule
import io.shamash.asm.core.engine.rules.RuleRegistry
import io.shamash.asm.core.facts.model.ClassFact
import io.shamash.asm.core.facts.query.FactIndex
import io.shamash.export.api.Exporters
import io.shamash.export.pipeline.ExportOrchestrator
import io.shamash.export.pipeline.ReportBuilder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import kotlin.io.path.name

/**
 * ASM engine entrypoint
 *
 * Responsibilities:
 * - Classify classes into roles (engine-owned)
 * - Expand rule defs into concrete rule instances (role variants)
 * - Execute rules best-effort (never crash the caller)
 * - Apply exception suppression (engine-owned)
 * - Apply/verify baseline suppression and/or generate baseline (engine-owned)
 * - Export reports (formats selection + overwrite behavior) via shamash-export
 * - Deterministic ordering of findings/errors
 */
class ShamashAsmEngine(
    private val registry: RuleRegistry = DefaultRuleRegistry.create(),
    private val toolName: String = "Shamash ASM",
    private val toolVersion: String = "dev",
) {
    fun analyze(
        projectBasePath: Path,
        projectName: String,
        config: ShamashAsmConfigV1,
        facts: FactIndex,
        generatedAtEpochMillis: Long = System.currentTimeMillis(),
        includeFactsInResult: Boolean = false,
    ): EngineResult {
        val startedAt = System.currentTimeMillis()

        val errors = mutableListOf<EngineError>()
        val findingsOut = mutableListOf<Finding>()

        // --- roles (engine-owned) -------------------------------------------------------------
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

        // --- rules ----------------------------------------------------------------------------
        var configuredRuleDefs = 0
        var executedRuleDefs = 0
        var skippedRuleDefs = 0

        // These are mapped into summary (locked-in).
        var executedRuleInstances = 0
        var skippedRuleInstances = 0
        var notFoundRuleInstances = 0
        var failedRuleInstances = 0

        for (ruleDef in config.rules) {
            configuredRuleDefs++

            if (!ruleDef.enabled) {
                skippedRuleDefs++
                continue
            }

            val type = ruleDef.type.trim()
            val name = ruleDef.name.trim()
            val baseRuleId = "$type.$name"

            val rule: Rule? = registry.resolve(ruleDef)

            if (rule == null) {
                notFoundRuleInstances++

                // IMPORTANT:
                // - UnknownRulePolicy.WARN should NOT fail the engine run (no EngineError).
                // - The warning belongs in ConfigValidation (ValidationError.WARNING).
                when (config.project.validation.unknownRule) {
                    UnknownRulePolicy.ERROR, UnknownRulePolicy.error -> {
                        errors += EngineError.ruleNotFound(baseRuleId)
                    }
                    UnknownRulePolicy.WARN, UnknownRulePolicy.warn -> {
                        // no-op (validation layer should surface this as WARNING)
                    }
                    UnknownRulePolicy.IGNORE, UnknownRulePolicy.ignore -> {
                        // no-op
                    }
                }

                skippedRuleDefs++
                continue
            }

            executedRuleDefs++

            val instanceRoles: List<String?> = expandInstanceRoles(ruleDef.roles)
            if (instanceRoles.isEmpty()) {
                // Nothing to execute (defensive; should not happen after schema validation)
                skippedRuleDefs++
                continue
            }

            for (role in instanceRoles) {
                val roleTrimmed = role?.trim()?.takeIf { it.isNotEmpty() }
                val instanceRuleId = RuleKey(type, name, roleTrimmed).canonicalId()

                // If the user explicitly excludes this role via scope, skip fast.
                val excludedByScope =
                    roleTrimmed != null && ruleDef.scope?.excludeRoles?.any { it.trim() == roleTrimmed } == true
                if (excludedByScope) {
                    skippedRuleInstances++
                    continue
                }

                val effectiveDef = applyRoleToRuleDef(ruleDef, roleTrimmed)

                try {
                    executedRuleInstances++

                    val produced: List<Finding> =
                        rule.evaluate(
                            facts = factsWithRoles, // engine-populated roles/classToRole
                            rule = effectiveDef, // per-instance RuleDef (role-scoped)
                            config = config, // full config
                        )

                    for (f in produced) {
                        findingsOut += normalizeFinding(f, instanceRuleId)
                    }
                } catch (pe: ParamError) {
                    failedRuleInstances++
                    errors +=
                        EngineError.ruleExecutionFailed(
                            ruleId = instanceRuleId,
                            message = "Rule param error: ${pe.message}",
                            details = mapOf("baseRuleId" to rule.id, "at" to pe.at),
                            t = pe,
                        )
                } catch (t: Throwable) {
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

        // --- exceptions (engine-owned) -------------------------------------------------------
        val afterExceptions =
            try {
                ExceptionSuppressor.compile(config.exceptions).suppress(findingsOut)
            } catch (t: Throwable) {
                errors += EngineError.internal("Failed to apply exceptions suppression", t = t)
                findingsOut.toList()
            }

        // --- baseline (engine-owned) ---------------------------------------------------------
        val baselinePath = resolveBaselinePath(projectBasePath, config.baseline.path)
        val (afterBaseline, baselineWritten) =
            try {
                applyBaseline(
                    projectBasePath = projectBasePath,
                    findings = afterExceptions,
                    mode = config.baseline.mode,
                    baselinePath = baselinePath,
                )
            } catch (t: Throwable) {
                errors +=
                    EngineError.baselineFailed(
                        message = "Baseline handling failed",
                        details = mapOf("path" to baselinePath.toString(), "mode" to config.baseline.mode.name),
                        t = t,
                    )
                afterExceptions to false
            }

        // Deterministic findings
        val findings = normalizeAndSortFindings(afterBaseline)

        // --- export via shamash-export -------------------------------------------------------
        val exportResult: EngineExportResult? =
            try {
                if (!config.export.enabled) {
                    null
                } else {
                    val outputDir = resolveExportDir(projectBasePath, config)
                    Files.createDirectories(outputDir)

                    if (!config.export.overwrite && anyRequestedReportExists(outputDir, config.export.formats)) {
                        null
                    } else {
                        val report =
                            exportReport(
                                projectBasePath = projectBasePath,
                                projectName = projectName,
                                outputDir = outputDir,
                                formats = config.export.formats,
                                findings = findings,
                                generatedAtEpochMillis = generatedAtEpochMillis,
                            )

                        EngineExportResult(
                            report = report,
                            outputDir = outputDir,
                            baselineWritten = baselineWritten,
                        )
                    }
                }
            } catch (t: Throwable) {
                errors +=
                    EngineError.exportFailed(
                        message = "Export failed",
                        details = mapOf("outputDir" to resolveExportDir(projectBasePath, config).toString()),
                        t = t,
                    )
                null
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
                export = exportResult,
                facts = if (includeFactsInResult) facts else null,
            )
        } else {
            EngineResult.failed(
                summary = summary,
                errors = stabilizedErrors,
                findings = findings,
                export = exportResult,
                facts = if (includeFactsInResult) facts else null,
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
                // Only set includeRoles if the user did not set it.
                if (scope0.includeRoles == null) {
                    scope0.copy(includeRoles = listOf(role))
                } else {
                    scope0
                }
            }

        // Also set roles to the concrete instance role (helps rules that consult rule.roles).
        return ruleDef.copy(roles = listOf(role), scope = scope)
    }

    private fun normalizeFinding(
        f: Finding,
        canonicalRuleId: String,
    ): Finding {
        // Normalize to forward-slash so:
        // - exceptions glob matching is stable
        // - baseline relativization is stable
        // - exported reports are consistent across OSes
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

    // -------------------------------------------------------------------------------------------------
    // Baseline
    // -------------------------------------------------------------------------------------------------

    private fun applyBaseline(
        projectBasePath: Path,
        findings: List<Finding>,
        mode: BaselineMode,
        baselinePath: Path,
    ): Pair<List<Finding>, Boolean> {
        if (mode == BaselineMode.NONE) return findings to false

        if (mode == BaselineMode.GENERATE) {
            val fps = computeFingerprints(projectBasePath, findings)
            writeBaselineFile(baselinePath, fps)
            return findings to true
        }

        // VERIFY
        val baselineFingerprints: Set<String> = loadBaselineFile(baselinePath)
        if (baselineFingerprints.isEmpty()) return findings to false

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
        return out to false
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

    private fun loadBaselineFile(path: Path): Set<String> {
        if (!Files.exists(path)) return emptySet()
        val raw = Files.readString(path, StandardCharsets.UTF_8)
        val version = parseIntField(raw, "version")
        if (version != 1) {
            throw IllegalStateException("Unsupported baseline version $version in $path (supported: 1).")
        }
        return parseStringArrayField(raw, "fingerprints")
    }

    private fun writeBaselineFile(
        path: Path,
        fingerprints: Set<String>,
    ) {
        Files.createDirectories(path.parent)
        val json = serializeBaselineJson(fingerprints)

        val tmp = path.resolveSibling("${path.name}.tmp")
        Files.writeString(tmp, json, StandardCharsets.UTF_8)

        try {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun serializeBaselineJson(fingerprints: Set<String>): String {
        val sb = StringBuilder(64 + fingerprints.size * 72)
        sb.append("{\n")
        sb.append("  \"version\": 1,\n")
        sb.append("  \"fingerprints\": [")

        var first = true
        for (fp in fingerprints.sorted()) {
            if (!first) sb.append(',')
            sb
                .append('\n')
                .append("    \"")
                .append(escapeJson(fp))
                .append('"')
            first = false
        }

        if (fingerprints.isNotEmpty()) sb.append('\n').append("  ")
        sb.append("]\n")
        sb.append("}\n")
        return sb.toString()
    }

    private fun escapeJson(s: String): String {
        val out = StringBuilder(s.length + 16)
        for (ch in s) {
            when (ch) {
                '\\' -> out.append("\\\\")
                '"' -> out.append("\\\"")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> out.append(ch)
            }
        }
        return out.toString()
    }

    private fun parseIntField(
        json: String,
        fieldName: String,
    ): Int {
        val idx = json.indexOf("\"$fieldName\"")
        if (idx < 0) throw IllegalStateException("Missing field \"$fieldName\" in baseline JSON.")
        val colon = json.indexOf(':', idx)
        if (colon < 0) throw IllegalStateException("Invalid baseline JSON: missing ':' after \"$fieldName\".")
        var i = colon + 1
        while (i < json.length && json[i].isWhitespace()) i++
        val start = i
        while (i < json.length && (json[i].isDigit() || json[i] == '-')) i++
        if (start == i) throw IllegalStateException("Invalid baseline JSON: \"$fieldName\" is not a number.")
        return json.substring(start, i).trim().toInt()
    }

    private fun parseStringArrayField(
        json: String,
        fieldName: String,
    ): Set<String> {
        val idx = json.indexOf("\"$fieldName\"")
        if (idx < 0) throw IllegalStateException("Missing field \"$fieldName\" in baseline JSON.")
        val colon = json.indexOf(':', idx)
        if (colon < 0) throw IllegalStateException("Invalid baseline JSON: missing ':' after \"$fieldName\".")
        val openBracket = json.indexOf('[', colon)
        if (openBracket < 0) throw IllegalStateException("Invalid baseline JSON: missing '[' for \"$fieldName\".")
        val closeBracket = json.indexOf(']', openBracket)
        if (closeBracket < 0) throw IllegalStateException("Invalid baseline JSON: missing ']' for \"$fieldName\".")
        val slice = json.substring(openBracket + 1, closeBracket)
        return extractJsonStringLiterals(slice)
    }

    private fun extractJsonStringLiterals(slice: String): Set<String> {
        val out = LinkedHashSet<String>()
        var i = 0
        while (i < slice.length) {
            while (i < slice.length && slice[i].isWhitespace()) i++
            if (i >= slice.length) break
            if (slice[i] != '"') {
                i++
                continue
            }

            i++ // opening quote
            val sb = StringBuilder()
            while (i < slice.length) {
                val ch = slice[i]
                when (ch) {
                    '"' -> {
                        i++
                        break
                    }
                    '\\' -> {
                        if (i + 1 >= slice.length) throw IllegalStateException("Invalid baseline JSON: trailing escape.")
                        val next = slice[i + 1]
                        when (next) {
                            '"', '\\', '/' -> sb.append(next)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 5 >= slice.length) throw IllegalStateException("Invalid baseline JSON: incomplete unicode escape.")
                                val hex = slice.substring(i + 2, i + 6)
                                sb.append(hex.toInt(16).toChar())
                                i += 4
                            }
                            else -> throw IllegalStateException("Invalid baseline JSON: unsupported escape \\$next.")
                        }
                        i += 2
                        continue
                    }
                    else -> {
                        sb.append(ch)
                        i++
                    }
                }
            }
            out += sb.toString()
            while (i < slice.length && slice[i] != '"') i++
        }
        return out
    }

    private fun resolveBaselinePath(
        projectBasePath: Path,
        raw: String,
    ): Path {
        val p = resolvePath(projectBasePath, raw)
        val s = p.toString()
        return if (s.endsWith(".json")) p else p.resolve("baseline.json").normalize()
    }

    // -------------------------------------------------------------------------------------------------
    // Export
    // -------------------------------------------------------------------------------------------------

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

    private fun anyRequestedReportExists(
        outputDir: Path,
        formats: List<ExportFormat>,
    ): Boolean {
        for (f in formats.toSet()) {
            val p =
                when (f) {
                    ExportFormat.JSON -> outputDir.resolve(ExportOutputLayout.JSON_FILE_NAME)
                    ExportFormat.SARIF -> outputDir.resolve(ExportOutputLayout.SARIF_FILE_NAME)
                    ExportFormat.XML -> outputDir.resolve(ExportOutputLayout.XML_FILE_NAME)
                    ExportFormat.HTML -> outputDir.resolve(ExportOutputLayout.HTML_FILE_NAME)
                }
            if (Files.exists(p)) return true
        }
        return false
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
        return ExportOutputLayout.normalizeOutputDir(projectBasePath, chosen)
    }

    private fun ExportFormat.toExporterFormat(): Exporters.Format? =
        when (this) {
            ExportFormat.JSON -> Exporters.Format.JSON
            ExportFormat.SARIF -> Exporters.Format.SARIF
            ExportFormat.XML -> Exporters.Format.XML
            ExportFormat.HTML -> Exporters.Format.HTML
        }

    // -------------------------------------------------------------------------------------------------
    // Paths
    // -------------------------------------------------------------------------------------------------

    private fun resolvePath(
        projectBasePath: Path,
        raw: String,
    ): Path {
        val p = Paths.get(raw)
        return (if (p.isAbsolute) p else projectBasePath.resolve(p)).normalize()
    }

    // -------------------------------------------------------------------------------------------------
    // Roles
    // -------------------------------------------------------------------------------------------------

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

        // Deterministic traversal
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

    // -------------------------------------------------------------------------------------------------
    // Exceptions
    // -------------------------------------------------------------------------------------------------

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
                // Rule
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

                // Role (ruleId convention: type.name.role)
                if (roles != null) {
                    val role = extractRoleFromRuleId(f.ruleId)
                    if (role == null || role !in roles) return false
                }

                // Class internal / name / package
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

                // Origin path
                if (originPathRegex != null) {
                    val origin = f.data["originPath"] ?: f.filePath
                    if (!originPathRegex.containsMatchIn(origin)) return false
                }

                // Glob
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
