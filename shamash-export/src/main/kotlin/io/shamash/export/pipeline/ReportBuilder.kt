/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.export.pipeline

import io.shamash.artifacts.baseline.BaselineFingerprint
import io.shamash.artifacts.contract.Finding
import io.shamash.artifacts.contract.FindingSeverity
import io.shamash.artifacts.report.schema.v1.ExportedFinding
import io.shamash.artifacts.report.schema.v1.ExportedReport
import io.shamash.artifacts.report.schema.v1.ProjectMetadata
import io.shamash.artifacts.report.schema.v1.ToolMetadata
import io.shamash.artifacts.util.PathNormalizer
import java.nio.file.Path
import java.nio.file.Paths

class ReportBuilder(
    private val findingPreprocessors: List<FindingPreprocessor> = emptyList(),
) {
    fun build(
        projectBasePath: Path,
        projectName: String,
        toolName: String,
        toolVersion: String,
        findings: List<Finding>,
        generatedAtEpochMillis: Long,
    ): ExportedReport {
        val baseAbs: Path = projectBasePath.toAbsolutePath().normalize()
        val normalizedBasePath: String = PathNormalizer.normalize(baseAbs.toString())

        val preprocessedFindings =
            applyPreprocessors(
                projectBasePath = baseAbs,
                findings = findings,
            )

        val exportedFindings =
            preprocessedFindings
                .asSequence()
                .map { toExportedFinding(baseAbs, it) }
                .sortedWith(EXPORTED_FINDING_COMPARATOR)
                .toList()

        return ExportedReport(
            tool =
                ToolMetadata(
                    name = toolName,
                    version = toolVersion,
                    schemaVersion = SCHEMA_VERSION,
                    generatedAtEpochMillis = generatedAtEpochMillis,
                ),
            project =
                ProjectMetadata(
                    name = projectName,
                    basePath = normalizedBasePath,
                ),
            findings = exportedFindings,
        )
    }

    private fun applyPreprocessors(
        projectBasePath: Path,
        findings: List<Finding>,
    ): List<Finding> {
        if (findingPreprocessors.isEmpty()) return findings

        var current: List<Finding> = findings
        for (preprocessor in findingPreprocessors) {
            current = preprocessor.process(projectBasePath, current)
        }
        return current
    }

    private fun toExportedFinding(
        projectBasePath: Path,
        finding: Finding,
    ): ExportedFinding {
        val filePath = toProjectRelativeNormalizedPath(projectBasePath, finding.filePath)
        val fingerprint = BaselineFingerprint.sha256Hex(finding, filePath)

        return ExportedFinding(
            ruleId = finding.ruleId,
            message = finding.message,
            severity = finding.severity,
            filePath = filePath,
            classFqn = normalizeOptional(finding.classFqn),
            memberName = normalizeOptional(finding.memberName),
            fingerprint = fingerprint,
        )
    }

    private fun toProjectRelativeNormalizedPath(
        projectBasePath: Path,
        rawFilePath: String,
    ): String {
        val target = safeToPath(rawFilePath)
        return if (target != null) {
            PathNormalizer.relativizeOrNormalize(projectBasePath, target)
        } else {
            PathNormalizer.normalize(rawFilePath)
        }
    }

    private fun safeToPath(raw: String): Path? =
        try {
            Paths.get(raw)
        } catch (_: Throwable) {
            null
        }

    private fun normalizeOptional(value: String?): String? {
        val v = value?.trim()
        return if (v.isNullOrEmpty()) null else v
    }

    private companion object {
        private const val SCHEMA_VERSION = "v1"

        private val EXPORTED_FINDING_COMPARATOR: Comparator<ExportedFinding> =
            Comparator { a, b ->
                val fileCmp = a.filePath.compareTo(b.filePath)
                if (fileCmp != 0) return@Comparator fileCmp

                val ruleCmp = a.ruleId.compareTo(b.ruleId)
                if (ruleCmp != 0) return@Comparator ruleCmp

                val sevCmp = severityRank(a).compareTo(severityRank(b))
                if (sevCmp != 0) return@Comparator sevCmp

                val classCmp = (a.classFqn ?: "").compareTo(b.classFqn ?: "")
                if (classCmp != 0) return@Comparator classCmp

                val memberCmp = (a.memberName ?: "").compareTo(b.memberName ?: "")
                if (memberCmp != 0) return@Comparator memberCmp

                // fingerprint is intended stable; message is not.
                val fpCmp = a.fingerprint.compareTo(b.fingerprint)
                if (fpCmp != 0) return@Comparator fpCmp

                a.message.compareTo(b.message)
            }

        private fun severityRank(f: ExportedFinding): Int =
            when (f.severity) {
                FindingSeverity.ERROR -> 0
                FindingSeverity.WARNING -> 1
                else -> 2
            }
    }
}
