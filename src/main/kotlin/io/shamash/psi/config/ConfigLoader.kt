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
import io.shamash.psi.config.schema.v1.model.RootPackageConfigV1
import io.shamash.psi.config.schema.v1.model.RootPackageModeV1
import io.shamash.psi.config.schema.v1.model.Rule
import io.shamash.psi.config.schema.v1.model.RuleScope
import io.shamash.psi.config.schema.v1.model.Severity
import io.shamash.psi.config.schema.v1.model.ShamashException
import io.shamash.psi.config.schema.v1.model.ShamashPsiConfigV1
import io.shamash.psi.config.schema.v1.model.SourceGlobsV1
import io.shamash.psi.config.schema.v1.model.UnknownRuleIdPolicyV1
import io.shamash.psi.config.schema.v1.model.ValidationConfigV1
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.io.Reader

object ConfigLoader {
    private val load: Load =
        Load(
            LoadSettings
                .builder()
                .setLabel("shamash-psi.schema")
                .build(),
        )

    fun loadRaw(reader: Reader): Any? = load.loadFromReader(reader)

    fun bindV1(raw: Any?): ShamashPsiConfigV1 {
        val root = raw as? Map<*, *> ?: error("Config root must be a mapping/object")
        val m = root.toStringKeyMap()

        val version = (m["version"] as? Number)?.toInt() ?: 1

        val project = bindProject(m["project"] as? Map<*, *>)
        val roles = bindRoles(m["roles"] as? Map<*, *> ?: emptyMap<Any, Any>())
        val rules = bindRules(m["rules"] as? Map<*, *> ?: emptyMap<Any, Any>())
        val exceptions = bindExceptions(m["exceptions"] as? List<*> ?: emptyList<Any>())

        return ShamashPsiConfigV1(
            version = version,
            project = project,
            roles = roles,
            rules = rules,
            shamashExceptions = exceptions,
        )
    }

    private fun bindProject(map: Map<*, *>?): ProjectConfigV1 {
        val m = (map ?: emptyMap<String, Any?>()).toStringKeyMap()

        val rootPkg = (m["rootPackage"] as? Map<*, *>)?.toStringKeyMap() ?: emptyMap()
        val modeRaw = (rootPkg["mode"] as? String)?.uppercase() ?: "AUTO"
        val mode = RootPackageModeV1.valueOf(modeRaw)
        val value = rootPkg["value"] as? String ?: ""

        val globs = (m["sourceGlobs"] as? Map<*, *>)?.toStringKeyMap() ?: emptyMap()
        val include = (globs["include"] as? List<*>)?.mapNotNull { it as? String } ?: listOf("src/main/java/**", "src/main/kotlin/**")
        val exclude =
            (globs["exclude"] as? List<*>)?.mapNotNull { it as? String } ?: listOf("**/build/**", "**/generated/**", "**/.idea/**")

        val validation = (m["validation"] as? Map<*, *>)?.toStringKeyMap() ?: emptyMap()
        val unknownPolicyRaw = (validation["unknownRuleId"] as? String)?.uppercase() ?: "WARN"
        val unknownPolicy = UnknownRuleIdPolicyV1.valueOf(unknownPolicyRaw)

        return ProjectConfigV1(
            rootPackage = RootPackageConfigV1(mode = mode, value = value),
            sourceGlobs = SourceGlobsV1(include = include, exclude = exclude),
            validation = ValidationConfigV1(unknownRuleId = unknownPolicy),
        )
    }

    private fun bindRoles(map: Map<*, *>): Map<String, Role> {
        val out = LinkedHashMap<String, Role>()
        map.forEach { (k, v) ->
            val roleName = k as? String ?: return@forEach
            val rm = (v as? Map<*, *>)?.toStringKeyMap() ?: return@forEach

            val priority =
                (rm["priority"] as? Number)?.toInt()
                    ?: error("roles.$roleName.priority is required")

            val desc = rm["description"] as? String
            val match = bindMatcher(rm["match"], "roles.$roleName.match")

            out[roleName] = Role(description = desc, priority = priority, match = match)
        }
        return out
    }

    private fun bindRules(map: Map<*, *>): Map<String, Rule> {
        val out = LinkedHashMap<String, Rule>()
        map.forEach { (k, v) ->
            val ruleId = k as? String ?: return@forEach
            val rm = (v as? Map<*, *>)?.toStringKeyMap() ?: return@forEach

            val enabled = rm["enabled"] as? Boolean ?: error("rules.$ruleId.enabled is required")
            val sevStr = rm["severity"] as? String ?: error("rules.$ruleId.severity is required")
            val severity = Severity.valueOf(sevStr.uppercase())

            val scope = (rm["scope"] as? Map<*, *>)?.toStringKeyMap()?.let { bindScope(it) }

            /*
             * Rule parameters:
             *
             * The v1 model uses Rule.params (Map<String, Any?>).
             *
             * We support two authoring styles for backwards-compatibility:
             *  1) Preferred: rule-specific settings nested under "params"
             *       rules:
             *         some.rule:
             *           enabled: true
             *           severity: WARNING
             *           params:
             *             banned: ["Service"]
             *
             *  2) Legacy/inline: rule-specific settings as direct properties of the rule object
             *       rules:
             *         some.rule:
             *           enabled: true
             *           severity: WARNING
             *           banned: ["Service"]
             *
             * We merge inline properties with nested "params". If the same key exists in both,
             * the nested params value wins (it's more explicit).
             */
            val inlineParams: Map<String, Any?> =
                rm.filterKeys { it != "enabled" && it != "severity" && it != "scope" && it != "params" }

            val nestedParams: Map<String, Any?> =
                when (val p = rm["params"]) {
                    null -> emptyMap()
                    is Map<*, *> -> p.toStringKeyMap()
                    else -> error("rules.$ruleId.params must be an object/map")
                }

            val mergedParams =
                if (nestedParams.isEmpty()) {
                    inlineParams
                } else if (inlineParams.isEmpty()) {
                    nestedParams
                } else {
                    // nested params override inline keys
                    LinkedHashMap<String, Any?>(inlineParams.size + nestedParams.size).apply {
                        putAll(inlineParams)
                        putAll(nestedParams)
                    }
                }

            out[ruleId] = Rule(enabled = enabled, severity = severity, scope = scope, params = mergedParams)
        }
        return out
    }

    private fun bindScope(map: Map<String, Any?>): RuleScope {
        fun listStr(key: String): List<String>? = (map[key] as? List<*>)?.mapNotNull { it as? String }

        return RuleScope(
            includeRoles = listStr("includeRoles"),
            excludeRoles = listStr("excludeRoles"),
            includePackages = listStr("includePackages"),
            excludePackages = listStr("excludePackages"),
            includeGlobs = listStr("includeGlobs"),
            excludeGlobs = listStr("excludeGlobs"),
        )
    }

    private fun bindExceptions(list: List<*>): List<ShamashException> {
        val out = ArrayList<ShamashException>()
        list.forEachIndexed { idx, item ->
            val m =
                (item as? Map<*, *>)?.toStringKeyMap()
                    ?: error("exceptions[$idx] must be an object")

            val id = m["id"] as? String ?: error("exceptions[$idx].id is required")
            val reason = m["reason"] as? String ?: error("exceptions[$idx].reason is required")
            val expiresOn = m["expiresOn"] as? String

            val suppress =
                (m["suppress"] as? List<*>)?.mapNotNull { it as? String }
                    ?: error("exceptions[$idx].suppress must be a list of strings")

            val matchMap =
                (m["match"] as? Map<*, *>)?.toStringKeyMap()
                    ?: error("exceptions[$idx].match is required")

            val match =
                ExceptionMatch(
                    fileGlob = matchMap["fileGlob"] as? String,
                    packageRegex = matchMap["packageRegex"] as? String,
                    classNameRegex = matchMap["classNameRegex"] as? String,
                    methodNameRegex = matchMap["methodNameRegex"] as? String,
                    fieldNameRegex = matchMap["fieldNameRegex"] as? String,
                    hasAnnotation = matchMap["hasAnnotation"] as? String,
                    hasAnnotationPrefix = matchMap["hasAnnotationPrefix"] as? String,
                    role = matchMap["role"] as? String,
                )

            out +=
                io.shamash.psi.config.schema.v1.model.ShamashException(
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
        val m = (any as? Map<*, *>)?.toStringKeyMap() ?: error("$path must be an object")

        if (m.containsKey("anyOf")) {
            val arr = m["anyOf"] as? List<*> ?: error("$path.anyOf must be a list")
            return Matcher.AnyOf(arr.mapIndexed { i, it -> bindMatcher(it, "$path.anyOf[$i]") })
        }
        if (m.containsKey("allOf")) {
            val arr = m["allOf"] as? List<*> ?: error("$path.allOf must be a list")
            return Matcher.AllOf(arr.mapIndexed { i, it -> bindMatcher(it, "$path.allOf[$i]") })
        }
        if (m.containsKey("not")) {
            return Matcher.Not(bindMatcher(m["not"], "$path.not"))
        }

        fun str(key: String) = m[key] as? String

        fun bool(key: String) = m[key] as? Boolean

        str("annotation")?.let { return Matcher.Annotation(it) }
        str("annotationPrefix")?.let { return Matcher.AnnotationPrefix(it) }
        str("packageRegex")?.let { return Matcher.PackageRegex(it) }
        str("packageContainsSegment")?.let { return Matcher.PackageContainsSegment(it) }
        str("classNameRegex")?.let { return Matcher.ClassNameRegex(it) }
        str("classNameEndsWith")?.let { return Matcher.ClassNameEndsWith(it) }

        (m["classNameEndsWithAny"] as? List<*>)?.mapNotNull { it as? String }?.let { list ->
            if (list.isNotEmpty()) return Matcher.ClassNameEndsWithAny(list)
        }

        bool("hasMainMethod")?.let { return Matcher.HasMainMethod(it) }
        str("implements")?.let { return Matcher.Implements(it) }
        str("extends")?.let { return Matcher.Extends(it) }

        error("$path must define a valid matcher")
    }

    private fun Map<*, *>.toStringKeyMap(): Map<String, Any?> = entries.associate { it.key.toString() to it.value }
}
