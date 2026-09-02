/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
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
package io.shamash.psi.core.config.schema.v1.model

import java.time.LocalDate

typealias RoleId = String

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
    /** Higher priorities win when multiple roles match; ties are resolved by role name. */
    val priority: Int,
    val match: Matcher,
)

/** Canonical ids are `type.name` for wildcard definitions or `type.name.role` for role instances. */
data class RuleDef(
    val type: String,
    val name: String,
    val roles: List<RoleId>?, // null = wildcard definition for (type,name)
    val enabled: Boolean,
    val severity: Severity,
    val scope: RuleScope?,
    val params: Map<String, Any?>,
)

enum class Severity { ERROR, WARNING, INFO }

/** Package filters are regexes; exclusions take precedence over inclusions. */
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
    val role: RoleId?,
) {
    fun canonicalId(): String = if (role == null) "$type.$name" else "$type.$name.$role"
}

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
