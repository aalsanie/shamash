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

/**
 * PSI Config V1 schema.
 */
data class ShamashPsiConfigV1(
    val version: Int = 1,
    val project: ProjectConfigV1 = ProjectConfigV1(),
    val roles: Map<String, Role> = emptyMap(),
    val rules: Map<String, Rule> = emptyMap(),
    val shamashExceptions: List<ShamashException> = emptyList(),
)

data class ProjectConfigV1(
    val rootPackage: RootPackageConfigV1 = RootPackageConfigV1(),
    val sourceGlobs: SourceGlobsV1 = SourceGlobsV1(),
    val validation: ValidationConfigV1 = ValidationConfigV1(),
)

data class ValidationConfigV1(
    val unknownRuleId: UnknownRuleIdPolicyV1 = UnknownRuleIdPolicyV1.WARN,
)

enum class UnknownRuleIdPolicyV1 { WARN, ERROR, IGNORE }

data class RootPackageConfigV1(
    val mode: RootPackageModeV1 = RootPackageModeV1.AUTO,
    val value: String = "",
)

enum class RootPackageModeV1 { AUTO, EXPLICIT }

data class SourceGlobsV1(
    val include: List<String> = listOf("src/main/java/**", "src/main/kotlin/**"),
    val exclude: List<String> = listOf("**/build/**", "**/generated/**", "**/.idea/**"),
)

/**
 * A role definition

// in DDD it can be application, domain, infra.
// in hexagonal it can be roles port, adapter, application, domain
// in microservices it can be api, service, client
// in layered/mvc it can roles, controller, service, repository
// etc... you decide what a role should be, no enforced nonsense
*/
data class Role(
    val description: String? = null,
    val priority: Int,
    val match: Matcher,
)

/**
 * Rule
 *
 * schema v1 validates envelope structurally, while
 * rule specific settings are validated by the rule implementation.
 */
data class Rule(
    val enabled: Boolean,
    val severity: Severity,
    val scope: RuleScope? = null,
    val params: Map<String, Any?> = emptyMap(),
)

enum class Severity { ERROR, WARNING, INFO }

data class RuleScope(
    val includeRoles: List<String>? = null,
    val excludeRoles: List<String>? = null,
    val includePackages: List<String>? = null, // regex
    val excludePackages: List<String>? = null, // regex
    val includeGlobs: List<String>? = null,
    val excludeGlobs: List<String>? = null,
)

/**
 * Exceptions (suppressions).
 */
data class ShamashException(
    val id: String,
    val reason: String,
    val expiresOn: String? = null,
    val match: ExceptionMatch,
    val suppress: List<String>,
)

data class ExceptionMatch(
    val fileGlob: String? = null,
    val packageRegex: String? = null,
    val classNameRegex: String? = null,
    val methodNameRegex: String? = null,
    val fieldNameRegex: String? = null,
    val hasAnnotation: String? = null,
    val hasAnnotationPrefix: String? = null,
    val role: String? = null,
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
