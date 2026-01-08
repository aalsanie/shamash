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
package io.shamash.psi.config.schema.v1.model

import java.time.LocalDate

typealias RoleId = String

/**
 * PSI Config V1 schema.
 *
 * NOTE: Models are intentionally validation-agnostic.
 * All invariants and semantic checks are enforced by the validator.
 */
data class ShamashPsiConfigV1(
    val version: Int,
    val project: ProjectConfigV1,
    val roles: Map<RoleId, Role>,
    val rules: List<RuleDef>,
    val shamashExceptions: List<ShamashException>,
)

data class ProjectConfigV1(
    val rootPackage: RootPackageConfigV1?,
    val sourceGlobs: SourceGlobsV1,
    val validation: ValidationConfigV1,
)

data class ValidationConfigV1(
    /**
     * Policy when a rule definition references an unknown (type,name) pair
     * (i.e., no registered RuleFactory/RuleSpec exists for it).
     */
    val unknownRule: UnknownRulePolicyV1,
)

enum class UnknownRulePolicyV1 { WARN, ERROR, IGNORE }

data class RootPackageConfigV1(
    val mode: RootPackageModeV1,
    val value: String,
)

enum class RootPackageModeV1 { AUTO, EXPLICIT }

data class SourceGlobsV1(
    val include: List<String>,
    val exclude: List<String>,
)

data class Role(
    val description: String?,
    /**
     * Role precedence used for deterministic resolution when multiple roles match the same target.
     * Validator enforces: 0 <= priority <= 100.
     */
    val priority: Int,
    val match: Matcher,
)

/**
 * Rule definition (authored).
 *
 * Identity is NOT user-defined. The engine derives canonical ids:
 * - wildcard:  type.name
 * - specific:  type.name.role
 *
 * Wildcard is expressed by roles == null.
 */
data class RuleDef(
    val type: String,
    val name: String,
    val roles: List<RoleId>?, // null = wildcard definition for (type,name)
    val enabled: Boolean,
    val severity: Severity,
    val scope: RuleScope?,
    /**
     * Rule-specific configuration bag. Typed reading is done via the external Params reader.
     */
    val params: Map<String, Any?>,
)

enum class Severity { ERROR, WARNING, INFO }

/**
 * Scope defines which targets are evaluated by a rule.
 *
 * Contract is enforced by validator/engine:
 * - excludes win over includes
 * - if any include* list is present => must match at least one include*
 * - else included by default
 *
 * includePackages/excludePackages are regexes.
 */
data class RuleScope(
    val includeRoles: List<RoleId>?,
    val excludeRoles: List<RoleId>?,
    val includePackages: List<String>?,
    val excludePackages: List<String>?,
    val includeGlobs: List<String>?,
    val excludeGlobs: List<String>?,
)

/**
 * Internal identity key for rule instances.
 * role == null indicates wildcard.
 */
data class RuleKey(
    val type: String,
    val name: String,
    val role: RoleId?,
) {
    fun canonicalId(): String = if (role == null) "$type.$name" else "$type.$name.$role"
}

/**
 * Exceptions (suppressions).
 */
data class ShamashException(
    val id: String,
    val reason: String,
    val expiresOn: LocalDate?,
    val match: ExceptionMatch,
    val suppress: List<String>,
)

data class ExceptionMatch(
    val fileGlob: String?,
    val packageRegex: String?,
    val classNameRegex: String?,
    val methodNameRegex: String?,
    val fieldNameRegex: String?,
    val hasAnnotation: String?,
    val hasAnnotationPrefix: String?,
    val role: RoleId?,
)

/**
 * Matcher DSL for roles.
 * Implemented as a sealed hierarchy to make evaluation explicit and safe.
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

    data class Annotation(
        val annotation: String,
    ) : Matcher

    data class AnnotationPrefix(
        val annotationPrefix: String,
    ) : Matcher

    data class PackageRegex(
        val packageRegex: String,
    ) : Matcher

    data class PackageContainsSegment(
        val packageContainsSegment: String,
    ) : Matcher

    data class ClassNameRegex(
        val classNameRegex: String,
    ) : Matcher

    data class ClassNameEndsWith(
        val classNameEndsWith: String,
    ) : Matcher

    data class ClassNameEndsWithAny(
        val classNameEndsWithAny: List<String>,
    ) : Matcher

    data class HasMainMethod(
        val hasMainMethod: Boolean,
    ) : Matcher

    data class Implements(
        val implements: String,
    ) : Matcher

    data class Extends(
        val extends: String,
    ) : Matcher
}
