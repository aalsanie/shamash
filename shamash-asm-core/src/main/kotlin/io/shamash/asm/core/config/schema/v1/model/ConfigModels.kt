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
package io.shamash.asm.core.config.schema.v1.model

import io.shamash.artifacts.contract.FindingSeverity

typealias RoleId = String

/**
 * ASM Config V1 models.
 */
data class ShamashAsmConfigV1(
    val version: Int,
    val project: ProjectConfig,
    val roles: Map<RoleId, RoleDef>,
    val analysis: AnalysisConfig,
    val rules: List<RuleDef>,
    val exceptions: List<ExceptionDef>,
    val baseline: BaselineConfig,
    val export: ExportConfig,
)

/* -----------------------------------------------------------------------------
 * Project / Scan
 * -------------------------------------------------------------------------- */

data class ProjectConfig(
    val bytecode: BytecodeConfig,
    val scan: ScanConfig,
    val validation: ValidationConfig,
)

data class BytecodeConfig(
    val roots: List<String>,
    val outputsGlobs: GlobSet,
    val jarGlobs: GlobSet,
)

data class GlobSet(
    val include: List<String>,
    val exclude: List<String>,
)

enum class ScanScope {
    PROJECT_WITH_EXTERNAL_BUCKETS,
    PROJECT_ONLY,
    ALL_SOURCES,
}

data class ScanConfig(
    val scope: ScanScope,
    val followSymlinks: Boolean,
    val maxClasses: Int?,
    val maxJarBytes: Int?,
    val maxClassBytes: Int?,
)

@Suppress("ktlint:standard:enum-entry-name-case")
enum class UnknownRulePolicy {
    ERROR,
    WARN,
    IGNORE,

    // schema tolerates lowercase too
    error,
    warn,
    ignore,
}

data class ValidationConfig(
    val unknownRule: UnknownRulePolicy,
)

/* -----------------------------------------------------------------------------
 * Roles
 * -------------------------------------------------------------------------- */

data class RoleDef(
    val priority: Int,
    val description: String?,
    val match: Matcher,
)

/**
 * Sealed matcher: enforces schema "oneOf" at the type level.
 *
 * Decoder responsibility:
 * - Convert the raw YAML object into exactly one of these variants.
 */
sealed interface Matcher {
    data class AnyOf(
        val anyOf: List<Matcher>,
    ) : Matcher

    data class AllOf(
        val allOf: List<Matcher>,
    ) : Matcher

    data class Not(
        val not: Matcher,
    ) : Matcher

    data class PackageRegex(
        val packageRegex: String,
    ) : Matcher

    data class PackageContainsSegment(
        val packageContainsSegment: String,
    ) : Matcher

    data class ClassNameEndsWith(
        val classNameEndsWith: String,
    ) : Matcher

    data class Annotation(
        val annotation: String,
    ) : Matcher

    data class AnnotationPrefix(
        val annotationPrefix: String,
    ) : Matcher
}

/* -----------------------------------------------------------------------------
 * Analysis
 * -------------------------------------------------------------------------- */

enum class Granularity {
    CLASS,
    PACKAGE,
    MODULE,
}

data class AnalysisConfig(
    val graphs: GraphsConfig,
    val hotspots: HotspotsConfig,
    val scoring: ScoringConfig,
)

data class GraphsConfig(
    val enabled: Boolean,
    val granularity: Granularity,
    val includeExternalBuckets: Boolean,
)

data class HotspotsConfig(
    val enabled: Boolean,
    val topN: Int,
    val includeExternal: Boolean,
)

enum class ScoreModel {
    V1,
}

data class ScoringConfig(
    val enabled: Boolean,
    val model: ScoreModel,
    val godClass: GodClassScoringConfig,
    val overall: OverallScoringConfig,
)

data class GodClassScoringConfig(
    val enabled: Boolean,
    val weights: GodClassWeights?, // optional override
    val thresholds: ScoreThresholds?, // optional override
)

data class OverallScoringConfig(
    val enabled: Boolean,
    val weights: OverallWeights?, // optional override
    val thresholds: ScoreThresholds?, // optional override
)

data class GodClassWeights(
    val methods: Double,
    val fields: Double,
    val fanOut: Double,
    val fanIn: Double,
    val packageSpread: Double,
)

data class OverallWeights(
    val cycles: Double,
    val dependencyDensity: Double,
    val layeringViolations: Double,
    val godClassPrevalence: Double,
    val externalCoupling: Double,
)

data class ScoreThresholds(
    val warning: Double,
    val error: Double,
)

/* -----------------------------------------------------------------------------
 * Rules / Exceptions
 * -------------------------------------------------------------------------- */

data class RuleDef(
    val type: String,
    val name: String,
    val roles: List<RoleId>?, // null = apply to all roles
    val enabled: Boolean,
    val severity: FindingSeverity,
    val scope: RuleScope?,
    val params: Map<String, Any?>,
)

data class RuleScope(
    val includeRoles: List<RoleId>?,
    val excludeRoles: List<RoleId>?,
    val includePackages: List<String>?,
    val excludePackages: List<String>?,
    val includeGlobs: List<String>?,
    val excludeGlobs: List<String>?,
)

data class RuleKey(
    val type: String,
    val name: String,
    val role: String? = null,
) {
    fun canonicalId(): String {
        val t = type.trim()
        val n = name.trim()
        val r = role?.trim()?.takeIf { it.isNotEmpty() }
        return if (r == null) "$t.$n" else "$t.$n.$r"
    }
}

data class ExceptionDef(
    val id: String,
    val enabled: Boolean,
    val match: ExceptionMatch,
    val reason: String,
)

data class ExceptionMatch(
    val ruleId: String?,
    val ruleType: String?,
    val ruleName: String?,
    val roles: List<RoleId>?,
    val classInternalName: String?,
    val classNameRegex: String?,
    val packageRegex: String?,
    val originPathRegex: String?,
    val glob: String?,
)

/* -----------------------------------------------------------------------------
 * Baseline / Export
 * -------------------------------------------------------------------------- */

enum class BaselineMode {
    NONE,
    GENERATE,
    VERIFY,
}

data class BaselineConfig(
    val mode: BaselineMode,
    val path: String,
)

enum class ExportFormat {
    JSON,
    SARIF,
    HTML,
    XML,
}

enum class ExportFactsFormat {
    /**
     * JSON Lines (.jsonl) compressed with gzip (.gz).
     *
     * Recommended for large projects: streamable.
     */
    JSONL_GZ,

    /**
     * A single JSON file. Not recommended for large codebases
     */
    JSON,
}

data class ExportArtifactsConfig(
    val facts: ExportFactsArtifactConfig? = null,
    val roles: ExportToggleArtifactConfig? = null,
    val rulePlan: ExportToggleArtifactConfig? = null,
    val analysis: ExportAnalysisArtifactsConfig? = null,
)

data class ExportFactsArtifactConfig(
    val enabled: Boolean,
    val format: ExportFactsFormat = ExportFactsFormat.JSONL_GZ,
)

data class ExportToggleArtifactConfig(
    val enabled: Boolean,
)

data class ExportAnalysisArtifactsConfig(
    val enabled: Boolean,
    val graphs: Boolean,
    val hotspots: Boolean,
    val scoring: Boolean,
)

data class ExportConfig(
    val enabled: Boolean,
    val outputDir: String,
    val formats: List<ExportFormat>,
    val overwrite: Boolean,
    val artifacts: ExportArtifactsConfig? = null,
)
