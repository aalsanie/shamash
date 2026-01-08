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
package io.shamash.psi.config

import io.shamash.psi.config.schema.v1.model.ExceptionMatch
import io.shamash.psi.config.schema.v1.model.Matcher
import io.shamash.psi.config.schema.v1.model.ProjectConfigV1
import io.shamash.psi.config.schema.v1.model.Role
import io.shamash.psi.config.schema.v1.model.RoleId
import io.shamash.psi.config.schema.v1.model.RootPackageConfigV1
import io.shamash.psi.config.schema.v1.model.RootPackageModeV1
import io.shamash.psi.config.schema.v1.model.RuleDef
import io.shamash.psi.config.schema.v1.model.RuleScope
import io.shamash.psi.config.schema.v1.model.Severity
import io.shamash.psi.config.schema.v1.model.ShamashException
import io.shamash.psi.config.schema.v1.model.ShamashPsiConfigV1
import io.shamash.psi.config.schema.v1.model.SourceGlobsV1
import io.shamash.psi.config.schema.v1.model.UnknownRulePolicyV1
import io.shamash.psi.config.schema.v1.model.ValidationConfigV1
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.io.Reader
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.LinkedHashMap

/**
 * Loads YAML and binds it into the locked schema v1 models.
 *
 * IMPORTANT BOUNDARY:
 * - This is a binder, not a semantic validator.
 * - It only enforces *shape/type* constraints required to construct schema models.
 * - Semantic rules (non-empty strings, priority ranges, wildcard rules, known roles, regex compilation, etc.)
 *   are enforced by the dedicated validation layer.
 */
object ConfigLoader {
    private val load: Load =
        Load(
            LoadSettings
                .builder()
                .setLabel("shamash-psi.schema")
                .build(),
        )

    /** Low-level YAML parse (raw tree). */
    fun loadRaw(reader: Reader): Any? = load.loadFromReader(reader)

    /**
     * Bind schema v1.
     *
     * @throws ConfigBindException if the raw YAML cannot be bound into schema v1 types.
     */
    fun bindV1(raw: Any?): ShamashPsiConfigV1 {
        val root = raw.asMap("root") ?: throw ConfigBindException("root", "Config root must be a mapping/object")

        val version = root.reqInt("version", "version")
        val project = bindProject(root.reqMap("project", "project"))
        val roles = bindRoles(root.reqMap("roles", "roles"))
        val rules = bindRules(root.reqList("rules", "rules"))
        val exceptions = bindExceptions(root.optList("exceptions") ?: emptyList())

        return ShamashPsiConfigV1(
            version = version,
            project = project,
            roles = roles,
            rules = rules,
            shamashExceptions = exceptions,
        )
    }

    private fun bindProject(map: Map<String, Any?>): ProjectConfigV1 {
        // Binder-only: optional fields remain optional; defaults are handled by validation layer if needed.
        val rootPackage = map.optMap("rootPackage")?.let { bindRootPackage(it) }
        val sourceGlobs = map.reqMap("sourceGlobs", "project.sourceGlobs").let { bindSourceGlobs(it) }

        // Binder-only: allow missing validation block; apply minimal default for binding.
        val validation =
            map.optMap("validation")?.let { bindValidation(it) }
                ?: ValidationConfigV1(unknownRule = UnknownRulePolicyV1.ERROR)

        return ProjectConfigV1(
            rootPackage = rootPackage,
            sourceGlobs = sourceGlobs,
            validation = validation,
        )
    }

    private fun bindRootPackage(map: Map<String, Any?>): RootPackageConfigV1 {
        val modeRaw = map.reqString("mode", "project.rootPackage.mode")
        val mode = modeRaw.toEnumOrThrow<RootPackageModeV1>("project.rootPackage.mode")

        // Binder-only: keep value as-is (string type); whether it must be present/non-blank depends on mode and is semantic.
        val value = map.optString("value")
        return RootPackageConfigV1(mode = mode, value = value ?: "")
    }

    private fun bindSourceGlobs(map: Map<String, Any?>): SourceGlobsV1 {
        val include = map.reqStringList("include", "project.sourceGlobs.include")
        val exclude = map.optStringList("exclude") ?: emptyList()
        return SourceGlobsV1(include = include, exclude = exclude)
    }

    private fun bindValidation(map: Map<String, Any?>): ValidationConfigV1 {
        val raw = map.reqString("unknownRule", "project.validation.unknownRule")
        val policy = raw.toEnumOrThrow<UnknownRulePolicyV1>("project.validation.unknownRule")
        return ValidationConfigV1(unknownRule = policy)
    }

    private fun bindRoles(map: Map<String, Any?>): Map<RoleId, Role> {
        val out = LinkedHashMap<RoleId, Role>(map.size)
        map.forEach { (roleId, any) ->
            val rm = any.asMap("roles.$roleId") ?: throw ConfigBindException("roles.$roleId", "roles.$roleId must be an object")
            val priority = rm.reqInt("priority", "roles.$roleId.priority")
            val description = rm.optString("description")
            val match = bindMatcher(rm.reqAny("match", "roles.$roleId.match"), "roles.$roleId.match")

            out[roleId] =
                Role(
                    description = description,
                    priority = priority,
                    match = match,
                )
        }
        return out
    }

    private fun bindRules(list: List<Any?>): List<RuleDef> {
        val out = ArrayList<RuleDef>(list.size)
        list.forEachIndexed { idx, item ->
            val path = "rules[$idx]"
            val m = item.asMap(path) ?: throw ConfigBindException(path, "$path must be an object")

            val type = m.reqString("type", "$path.type")
            val name = m.reqString("name", "$path.name")

            // Binder-only:
            // - roles may be missing => treat as null (semantic validator can require explicit presence if desired).
            // - roles may be null => wildcard.
            // - roles list values must be strings if present (type binding).
            val roles: List<RoleId>? =
                if (!m.containsKey("roles")) {
                    null
                } else {
                    when (val any = m["roles"]) {
                        null -> null
                        is List<*> ->
                            any.mapIndexed { i, r ->
                                r as? String ?: throw ConfigBindException("$path.roles[$i]", "$path.roles[$i] must be a string")
                            }
                        else -> throw ConfigBindException("$path.roles", "$path.roles must be a list of strings or null")
                    }
                }

            val enabled = m.reqBoolean("enabled", "$path.enabled")
            val severityRaw = m.reqString("severity", "$path.severity")
            val severity = severityRaw.toEnumOrThrow<Severity>("$path.severity")

            val scope = m.optMap("scope")?.let { bindScope(it, "$path.scope") }

            // Binder-only: params may be missing or null => emptyMap
            val params: Map<String, Any?> =
                if (!m.containsKey("params") || m["params"] == null) {
                    emptyMap()
                } else {
                    val pAny = m["params"]
                    when (pAny) {
                        is Map<*, *> -> pAny.toStringKeyMapOrdered()
                        else -> throw ConfigBindException("$path.params", "$path.params must be an object/map")
                    }
                }

            out +=
                RuleDef(
                    type = type,
                    name = name,
                    roles = roles,
                    enabled = enabled,
                    severity = severity,
                    scope = scope,
                    params = params,
                )
        }
        return out
    }

    private fun bindScope(
        map: Map<String, Any?>,
        path: String,
    ): RuleScope {
        fun listRole(key: String): List<RoleId>? =
            map.optList(key)?.mapIndexed { i, it ->
                it as? String ?: throw ConfigBindException("$path.$key[$i]", "$path.$key[$i] must be a string")
            }

        fun listStr(key: String): List<String>? =
            map.optList(key)?.mapIndexed { i, it ->
                it as? String ?: throw ConfigBindException("$path.$key[$i]", "$path.$key[$i] must be a string")
            }

        return RuleScope(
            includeRoles = listRole("includeRoles"),
            excludeRoles = listRole("excludeRoles"),
            includePackages = listStr("includePackages"),
            excludePackages = listStr("excludePackages"),
            includeGlobs = listStr("includeGlobs"),
            excludeGlobs = listStr("excludeGlobs"),
        )
    }

    private fun bindExceptions(list: List<Any?>): List<ShamashException> {
        val out = ArrayList<ShamashException>(list.size)
        list.forEachIndexed { idx, item ->
            val path = "exceptions[$idx]"
            val m = item.asMap(path) ?: throw ConfigBindException(path, "$path must be an object")

            val id = m.reqString("id", "$path.id")
            val reason = m.reqString("reason", "$path.reason")

            val expiresOn: LocalDate? =
                m.optString("expiresOn")?.let { s ->
                    val trimmed = s.trim()
                    if (trimmed.isEmpty()) {
                        null
                    } else {
                        try {
                            LocalDate.parse(trimmed)
                        } catch (e: DateTimeParseException) {
                            throw ConfigBindException("$path.expiresOn", "$path.expiresOn must be an ISO-8601 date (yyyy-MM-dd)")
                        }
                    }
                }

            val suppress = m.reqStringList("suppress", "$path.suppress")
            val matchMap = m.reqMap("match", "$path.match")

            val match =
                ExceptionMatch(
                    fileGlob = matchMap.optString("fileGlob"),
                    packageRegex = matchMap.optString("packageRegex"),
                    classNameRegex = matchMap.optString("classNameRegex"),
                    methodNameRegex = matchMap.optString("methodNameRegex"),
                    fieldNameRegex = matchMap.optString("fieldNameRegex"),
                    hasAnnotation = matchMap.optString("hasAnnotation"),
                    hasAnnotationPrefix = matchMap.optString("hasAnnotationPrefix"),
                    role = matchMap.optString("role"),
                )

            out +=
                ShamashException(
                    id = id,
                    reason = reason,
                    expiresOn = expiresOn,
                    match = match,
                    suppress = suppress,
                )
        }
        return out
    }

    private fun bindMatcher(
        any: Any?,
        path: String,
    ): Matcher {
        val m = any.asMap(path) ?: throw ConfigBindException(path, "$path must be an object")

        if (m.containsKey("anyOf")) {
            val arr = m.reqList("anyOf", "$path.anyOf")
            return Matcher.AnyOf(arr.mapIndexed { i, it -> bindMatcher(it, "$path.anyOf[$i]") })
        }
        if (m.containsKey("allOf")) {
            val arr = m.reqList("allOf", "$path.allOf")
            return Matcher.AllOf(arr.mapIndexed { i, it -> bindMatcher(it, "$path.allOf[$i]") })
        }
        if (m.containsKey("not")) {
            return Matcher.Not(bindMatcher(m.reqAny("not", "$path.not"), "$path.not"))
        }

        fun str(key: String): String? = m.optString(key)

        fun bool(key: String): Boolean? = m[key] as? Boolean

        str("annotation")?.let { return Matcher.Annotation(it) }
        str("annotationPrefix")?.let { return Matcher.AnnotationPrefix(it) }
        str("packageRegex")?.let { return Matcher.PackageRegex(it) }
        str("packageContainsSegment")?.let { return Matcher.PackageContainsSegment(it) }
        str("classNameRegex")?.let { return Matcher.ClassNameRegex(it) }
        str("classNameEndsWith")?.let { return Matcher.ClassNameEndsWith(it) }

        if (m.containsKey("classNameEndsWithAny")) {
            val raw = m["classNameEndsWithAny"]
            val list =
                (raw as? List<*>) ?: throw ConfigBindException("$path.classNameEndsWithAny", "$path.classNameEndsWithAny must be a list")
            val parsed =
                list.mapIndexed { i, it ->
                    it as? String
                        ?: throw ConfigBindException("$path.classNameEndsWithAny[$i]", "$path.classNameEndsWithAny[$i] must be a string")
                }
            return Matcher.ClassNameEndsWithAny(parsed)
        }

        bool("hasMainMethod")?.let { return Matcher.HasMainMethod(it) }
        str("implements")?.let { return Matcher.Implements(it) }
        str("extends")?.let { return Matcher.Extends(it) }

        throw ConfigBindException(path, "$path must define a valid matcher object")
    }

    // ---------------- errors + helpers ----------------

    class ConfigBindException(
        val path: String,
        override val message: String,
    ) : RuntimeException("$path: $message")

    private inline fun <reified E : Enum<E>> String.toEnumOrThrow(path: String): E {
        val normalized = trim().uppercase()
        return try {
            enumValueOf<E>(normalized)
        } catch (_: IllegalArgumentException) {
            val allowed = enumValues<E>().joinToString { it.name }
            throw ConfigBindException(path, "$path must be one of: $allowed")
        }
    }

    private fun Any?.asMap(path: String): Map<String, Any?>? =
        when (this) {
            null -> null
            is Map<*, *> -> this.toStringKeyMapOrdered()
            else -> throw ConfigBindException(path, "$path must be an object/map")
        }

    private fun Map<String, Any?>.reqAny(
        key: String,
        path: String,
    ): Any? = if (!containsKey(key)) throw ConfigBindException(path, "$path is required") else this[key]

    private fun Map<String, Any?>.reqMap(
        key: String,
        path: String,
    ): Map<String, Any?> = this[key].asMap(path) ?: throw ConfigBindException(path, "$path is required")

    private fun Map<String, Any?>.optMap(key: String): Map<String, Any?>? =
        when (val v = this[key]) {
            null -> null
            is Map<*, *> -> v.toStringKeyMapOrdered()
            else -> throw ConfigBindException(key, "$key must be an object/map")
        }

    private fun Map<String, Any?>.reqList(
        key: String,
        path: String,
    ): List<Any?> {
        val v = this[key] ?: throw ConfigBindException(path, "$path is required")
        @Suppress("UNCHECKED_CAST")
        return v as? List<Any?> ?: throw ConfigBindException(path, "$path must be a list")
    }

    private fun Map<String, Any?>.optList(key: String): List<Any?>? =
        when (val v = this[key]) {
            null -> null
            is List<*> -> {
                @Suppress("UNCHECKED_CAST")
                v as List<Any?>
            }
            else -> throw ConfigBindException(key, "$key must be a list")
        }

    private fun Map<String, Any?>.reqString(
        key: String,
        path: String,
    ): String {
        val v = this[key] ?: throw ConfigBindException(path, "$path is required")
        return v as? String ?: throw ConfigBindException(path, "$path must be a string")
    }

    private fun Map<String, Any?>.optString(key: String): String? =
        when (val v = this[key]) {
            null -> null
            is String -> v
            else -> throw ConfigBindException(key, "$key must be a string")
        }

    private fun Map<String, Any?>.reqInt(
        key: String,
        path: String,
    ): Int {
        val v = this[key] ?: throw ConfigBindException(path, "$path is required")
        val n = v as? Number ?: throw ConfigBindException(path, "$path must be a number")
        return n.toInt()
    }

    private fun Map<String, Any?>.reqBoolean(
        key: String,
        path: String,
    ): Boolean {
        val v = this[key] ?: throw ConfigBindException(path, "$path is required")
        return v as? Boolean ?: throw ConfigBindException(path, "$path must be a boolean")
    }

    private fun Map<String, Any?>.reqStringList(
        key: String,
        path: String,
    ): List<String> {
        val v = this[key] ?: throw ConfigBindException(path, "$path is required")
        val list = v as? List<*> ?: throw ConfigBindException(path, "$path must be a list")
        return list.mapIndexed { i, it ->
            it as? String ?: throw ConfigBindException("$path[$i]", "$path[$i] must be a string")
        }
    }

    private fun Map<String, Any?>.optStringList(key: String): List<String>? {
        val v = this[key] ?: return null
        val list = v as? List<*> ?: throw ConfigBindException(key, "$key must be a list")
        return list.mapIndexed { i, it ->
            it as? String ?: throw ConfigBindException("$key[$i]", "$key[$i] must be a string")
        }
    }

    private fun Map<*, *>.toStringKeyMapOrdered(): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>(this.size)
        for ((k, v) in this) out[k?.toString() ?: "null"] = v
        return out
    }
}
