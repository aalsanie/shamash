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
package io.shamash.asm.core.config

import io.shamash.artifacts.contract.FindingSeverity
import io.shamash.asm.core.config.schema.v1.model.AnalysisConfig
import io.shamash.asm.core.config.schema.v1.model.BaselineConfig
import io.shamash.asm.core.config.schema.v1.model.BaselineMode
import io.shamash.asm.core.config.schema.v1.model.BytecodeConfig
import io.shamash.asm.core.config.schema.v1.model.ExceptionDef
import io.shamash.asm.core.config.schema.v1.model.ExceptionMatch
import io.shamash.asm.core.config.schema.v1.model.ExportConfig
import io.shamash.asm.core.config.schema.v1.model.ExportFormat
import io.shamash.asm.core.config.schema.v1.model.GlobSet
import io.shamash.asm.core.config.schema.v1.model.GodClassScoringConfig
import io.shamash.asm.core.config.schema.v1.model.GodClassWeights
import io.shamash.asm.core.config.schema.v1.model.Granularity
import io.shamash.asm.core.config.schema.v1.model.GraphsConfig
import io.shamash.asm.core.config.schema.v1.model.HotspotsConfig
import io.shamash.asm.core.config.schema.v1.model.Matcher
import io.shamash.asm.core.config.schema.v1.model.OverallScoringConfig
import io.shamash.asm.core.config.schema.v1.model.OverallWeights
import io.shamash.asm.core.config.schema.v1.model.ProjectConfig
import io.shamash.asm.core.config.schema.v1.model.RoleDef
import io.shamash.asm.core.config.schema.v1.model.RoleId
import io.shamash.asm.core.config.schema.v1.model.RuleDef
import io.shamash.asm.core.config.schema.v1.model.RuleScope
import io.shamash.asm.core.config.schema.v1.model.ScanConfig
import io.shamash.asm.core.config.schema.v1.model.ScanScope
import io.shamash.asm.core.config.schema.v1.model.ScoreModel
import io.shamash.asm.core.config.schema.v1.model.ScoreThresholds
import io.shamash.asm.core.config.schema.v1.model.ScoringConfig
import io.shamash.asm.core.config.schema.v1.model.ShamashAsmConfigV1
import io.shamash.asm.core.config.schema.v1.model.UnknownRulePolicy
import io.shamash.asm.core.config.schema.v1.model.ValidationConfig
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.io.Reader
import java.util.LinkedHashMap
import kotlin.math.floor

object ConfigLoader {
    private val load: Load =
        Load(
            LoadSettings
                .builder()
                .setLabel("shamash-asm.schema")
                .build(),
        )

    fun loadRaw(reader: Reader): Any? = load.loadFromReader(reader)

    fun bindV1(raw: Any?): ShamashAsmConfigV1 {
        val root = raw.asMap("root") ?: throw ConfigBindException("root", "root must be an object/map")

        val version = root.reqInt("version", "version")
        val project = bindProject(root.reqMap("project", "project"))
        val roles = bindRoles(root.reqMap("roles", "roles"))
        val analysis = bindAnalysis(root.reqMap("analysis", "analysis"))
        val rules = bindRules(root.reqList("rules", "rules"))
        val exceptions = bindExceptions(root.optList("exceptions", "exceptions") ?: emptyList())
        val baseline = bindBaseline(root.reqMap("baseline", "baseline"))
        val export = bindExport(root.reqMap("export", "export"))

        return ShamashAsmConfigV1(
            version = version,
            project = project,
            roles = roles,
            analysis = analysis,
            rules = rules,
            exceptions = exceptions,
            baseline = baseline,
            export = export,
        )
    }

    private fun bindProject(map: Map<String, Any?>): ProjectConfig {
        val bytecode = bindBytecode(map.reqMap("bytecode", "project.bytecode"))
        val scan = bindScan(map.reqMap("scan", "project.scan"))

        val validation =
            map.optMap("validation", "project.validation")?.let { bindValidation(it) }
                ?: ValidationConfig(unknownRule = UnknownRulePolicy.ERROR)

        return ProjectConfig(
            bytecode = bytecode,
            scan = scan,
            validation = validation,
        )
    }

    private fun bindBytecode(map: Map<String, Any?>): BytecodeConfig {
        val roots = map.reqStringList("roots", "project.bytecode.roots")
        val outputsGlobs = bindGlobSet(map.reqMap("outputsGlobs", "project.bytecode.outputsGlobs"), "project.bytecode.outputsGlobs")
        val jarGlobs = bindGlobSet(map.reqMap("jarGlobs", "project.bytecode.jarGlobs"), "project.bytecode.jarGlobs")

        return BytecodeConfig(
            roots = roots,
            outputsGlobs = outputsGlobs,
            jarGlobs = jarGlobs,
        )
    }

    private fun bindGlobSet(
        map: Map<String, Any?>,
        path: String,
    ): GlobSet {
        val include = map.reqStringList("include", "$path.include")
        val exclude = map.optStringList("exclude", "$path.exclude") ?: emptyList()
        return GlobSet(include = include, exclude = exclude)
    }

    private fun bindScan(map: Map<String, Any?>): ScanConfig {
        val scopeRaw = map.reqString("scope", "project.scan.scope")
        val scope = scopeRaw.toEnumOrThrow<ScanScope>("project.scan.scope")

        val followSymlinks = map.reqBoolean("followSymlinks", "project.scan.followSymlinks")
        val maxClasses = map.optInt("maxClasses", "project.scan.maxClasses")
        val maxJarBytes = map.optInt("maxJarBytes", "project.scan.maxJarBytes")
        val maxClassBytes = map.optInt("maxClassBytes", "project.scan.maxClassBytes")

        return ScanConfig(
            scope = scope,
            followSymlinks = followSymlinks,
            maxClasses = maxClasses,
            maxJarBytes = maxJarBytes,
            maxClassBytes = maxClassBytes,
        )
    }

    private fun bindValidation(map: Map<String, Any?>): ValidationConfig {
        val raw = map.reqString("unknownRule", "project.validation.unknownRule")
        val policy = raw.toEnumOrThrow<UnknownRulePolicy>("project.validation.unknownRule")
        return ValidationConfig(unknownRule = policy)
    }

    private fun bindRoles(map: Map<String, Any?>): Map<RoleId, RoleDef> {
        val out = LinkedHashMap<RoleId, RoleDef>(map.size)
        map.forEach { (roleId, any) ->
            val rm = any.asMap("roles.$roleId") ?: throw ConfigBindException("roles.$roleId", "roles.$roleId must be an object/map")
            val priority = rm.reqInt("priority", "roles.$roleId.priority")
            val description = rm.optString("description", "roles.$roleId.description")
            val match = bindMatcher(rm.reqAny("match", "roles.$roleId.match"), "roles.$roleId.match")

            out[roleId] =
                RoleDef(
                    priority = priority,
                    description = description,
                    match = match,
                )
        }
        return out
    }

    private fun bindMatcher(
        any: Any?,
        path: String,
    ): Matcher {
        val m = any.asMap(path) ?: throw ConfigBindException(path, "$path must be an object/map")

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

        fun str(key: String): String? = m.optString(key, "$path.$key")

        str("annotation")?.let { return Matcher.Annotation(it) }
        str("annotationPrefix")?.let { return Matcher.AnnotationPrefix(it) }
        str("packageRegex")?.let { return Matcher.PackageRegex(it) }
        str("packageContainsSegment")?.let { return Matcher.PackageContainsSegment(it) }
        str("classNameEndsWith")?.let { return Matcher.ClassNameEndsWith(it) }

        throw ConfigBindException(path, "$path must define a valid matcher object")
    }

    private fun bindAnalysis(map: Map<String, Any?>): AnalysisConfig {
        val graphs = bindGraphs(map.reqMap("graphs", "analysis.graphs"))
        val hotspots = bindHotspots(map.reqMap("hotspots", "analysis.hotspots"))
        val scoring = bindScoring(map.reqMap("scoring", "analysis.scoring"))

        return AnalysisConfig(
            graphs = graphs,
            hotspots = hotspots,
            scoring = scoring,
        )
    }

    private fun bindGraphs(map: Map<String, Any?>): GraphsConfig {
        val enabled = map.reqBoolean("enabled", "analysis.graphs.enabled")
        val granularityRaw = map.reqString("granularity", "analysis.graphs.granularity")
        val granularity = granularityRaw.toEnumOrThrow<Granularity>("analysis.graphs.granularity")
        val includeExternalBuckets = map.reqBoolean("includeExternalBuckets", "analysis.graphs.includeExternalBuckets")

        return GraphsConfig(
            enabled = enabled,
            granularity = granularity,
            includeExternalBuckets = includeExternalBuckets,
        )
    }

    private fun bindHotspots(map: Map<String, Any?>): HotspotsConfig {
        val enabled = map.reqBoolean("enabled", "analysis.hotspots.enabled")
        val topN = map.reqInt("topN", "analysis.hotspots.topN")
        val includeExternal = map.reqBoolean("includeExternal", "analysis.hotspots.includeExternal")

        return HotspotsConfig(
            enabled = enabled,
            topN = topN,
            includeExternal = includeExternal,
        )
    }

    private fun bindScoring(map: Map<String, Any?>): ScoringConfig {
        val enabled = map.reqBoolean("enabled", "analysis.scoring.enabled")
        val modelRaw = map.reqString("model", "analysis.scoring.model")
        val model = modelRaw.toEnumOrThrow<ScoreModel>("analysis.scoring.model")

        val godClass = bindGodClass(map.reqMap("godClass", "analysis.scoring.godClass"))
        val overall = bindOverall(map.reqMap("overall", "analysis.scoring.overall"))

        return ScoringConfig(
            enabled = enabled,
            model = model,
            godClass = godClass,
            overall = overall,
        )
    }

    private fun bindGodClass(map: Map<String, Any?>): GodClassScoringConfig {
        val enabled = map.reqBoolean("enabled", "analysis.scoring.godClass.enabled")
        val weights =
            map.optMap("weights", "analysis.scoring.godClass.weights")?.let {
                bindGodClassWeights(it, "analysis.scoring.godClass.weights")
            }
        val thresholds =
            map.optMap("thresholds", "analysis.scoring.godClass.thresholds")?.let {
                bindThresholds(it, "analysis.scoring.godClass.thresholds")
            }

        return GodClassScoringConfig(
            enabled = enabled,
            weights = weights,
            thresholds = thresholds,
        )
    }

    private fun bindOverall(map: Map<String, Any?>): OverallScoringConfig {
        val enabled = map.reqBoolean("enabled", "analysis.scoring.overall.enabled")
        val weights =
            map
                .optMap(
                    "weights",
                    "analysis.scoring.overall.weights",
                )?.let { bindOverallWeights(it, "analysis.scoring.overall.weights") }
        val thresholds =
            map.optMap("thresholds", "analysis.scoring.overall.thresholds")?.let {
                bindThresholds(it, "analysis.scoring.overall.thresholds")
            }

        return OverallScoringConfig(
            enabled = enabled,
            weights = weights,
            thresholds = thresholds,
        )
    }

    private fun bindGodClassWeights(
        map: Map<String, Any?>,
        path: String,
    ): GodClassWeights {
        fun reqDouble(key: String): Double = map.reqDouble(key, "$path.$key")

        return GodClassWeights(
            methods = reqDouble("methods"),
            fields = reqDouble("fields"),
            fanOut = reqDouble("fanOut"),
            fanIn = reqDouble("fanIn"),
            packageSpread = reqDouble("packageSpread"),
        )
    }

    private fun bindOverallWeights(
        map: Map<String, Any?>,
        path: String,
    ): OverallWeights {
        fun reqDouble(key: String): Double = map.reqDouble(key, "$path.$key")

        return OverallWeights(
            cycles = reqDouble("cycles"),
            dependencyDensity = reqDouble("dependencyDensity"),
            layeringViolations = reqDouble("layeringViolations"),
            godClassPrevalence = reqDouble("godClassPrevalence"),
            externalCoupling = reqDouble("externalCoupling"),
        )
    }

    private fun bindThresholds(
        map: Map<String, Any?>,
        path: String,
    ): ScoreThresholds {
        val warning = map.reqDouble("warning", "$path.warning")
        val error = map.reqDouble("error", "$path.error")
        return ScoreThresholds(warning = warning, error = error)
    }

    private fun bindRules(list: List<Any?>): List<RuleDef> {
        val out = ArrayList<RuleDef>(list.size)
        list.forEachIndexed { idx, item ->
            val path = "rules[$idx]"
            val m = item.asMap(path) ?: throw ConfigBindException(path, "$path must be an object/map")

            val type = m.reqString("type", "$path.type")
            val name = m.reqString("name", "$path.name")

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
            val severity = severityRaw.toEnumOrThrow<FindingSeverity>("$path.severity")

            val scope = m.optMap("scope", "$path.scope")?.let { bindRuleScope(it, "$path.scope") }

            val params: Map<String, Any?> =
                if (!m.containsKey("params") || m["params"] == null) {
                    emptyMap()
                } else {
                    when (val pAny = m["params"]) {
                        is Map<*, *> -> pAny.toStringKeyMapOrdered("$path.params")
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

    private fun bindRuleScope(
        map: Map<String, Any?>,
        path: String,
    ): RuleScope {
        fun listRole(key: String): List<RoleId>? =
            map.optList(key, "$path.$key")?.mapIndexed { i, it ->
                it as? String ?: throw ConfigBindException("$path.$key[$i]", "$path.$key[$i] must be a string")
            }

        fun listStr(key: String): List<String>? =
            map.optList(key, "$path.$key")?.mapIndexed { i, it ->
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

    private fun bindExceptions(list: List<Any?>): List<ExceptionDef> {
        val out = ArrayList<ExceptionDef>(list.size)
        list.forEachIndexed { idx, item ->
            val path = "exceptions[$idx]"
            val m = item.asMap(path) ?: throw ConfigBindException(path, "$path must be an object/map")

            val id = m.reqString("id", "$path.id")
            val enabled = m.reqBoolean("enabled", "$path.enabled")
            val reason = m.reqString("reason", "$path.reason")

            val matchMap = m.reqMap("match", "$path.match")

            val match =
                ExceptionMatch(
                    ruleId = matchMap.optString("ruleId", "$path.match.ruleId"),
                    ruleType = matchMap.optString("ruleType", "$path.match.ruleType"),
                    ruleName = matchMap.optString("ruleName", "$path.match.ruleName"),
                    roles = matchMap.optStringList("roles", "$path.match.roles"),
                    classInternalName = matchMap.optString("classInternalName", "$path.match.classInternalName"),
                    classNameRegex = matchMap.optString("classNameRegex", "$path.match.classNameRegex"),
                    packageRegex = matchMap.optString("packageRegex", "$path.match.packageRegex"),
                    originPathRegex = matchMap.optString("originPathRegex", "$path.match.originPathRegex"),
                    glob = matchMap.optString("glob", "$path.match.glob"),
                )

            out +=
                ExceptionDef(
                    id = id,
                    enabled = enabled,
                    match = match,
                    reason = reason,
                )
        }
        return out
    }

    private fun bindBaseline(map: Map<String, Any?>): BaselineConfig {
        val modeRaw = map.reqString("mode", "baseline.mode")
        val mode = modeRaw.toEnumOrThrow<BaselineMode>("baseline.mode")
        val path = map.reqString("path", "baseline.path")
        return BaselineConfig(mode = mode, path = path)
    }

    private fun bindExport(map: Map<String, Any?>): ExportConfig {
        val enabled = map.reqBoolean("enabled", "export.enabled")
        val outputDir = map.reqString("outputDir", "export.outputDir")
        val overwrite = map.reqBoolean("overwrite", "export.overwrite")

        val formatsAny = map.reqList("formats", "export.formats")
        val formats =
            formatsAny.mapIndexed { i, it ->
                val s = it as? String ?: throw ConfigBindException("export.formats[$i]", "export.formats[$i] must be a string")
                s.toEnumOrThrow<ExportFormat>("export.formats[$i]")
            }

        return ExportConfig(
            enabled = enabled,
            outputDir = outputDir,
            formats = formats,
            overwrite = overwrite,
        )
    }

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
            is Map<*, *> -> this.toStringKeyMapOrdered(path)
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

    private fun Map<String, Any?>.optMap(
        key: String,
        path: String,
    ): Map<String, Any?>? =
        when (val v = this[key]) {
            null -> null
            is Map<*, *> -> v.toStringKeyMapOrdered(path)
            else -> throw ConfigBindException(path, "$path must be an object/map")
        }

    private fun Map<String, Any?>.reqList(
        key: String,
        path: String,
    ): List<Any?> {
        val v = this[key] ?: throw ConfigBindException(path, "$path is required")
        @Suppress("UNCHECKED_CAST")
        return v as? List<Any?> ?: throw ConfigBindException(path, "$path must be a list")
    }

    private fun Map<String, Any?>.optList(
        key: String,
        path: String,
    ): List<Any?>? =
        when (val v = this[key]) {
            null -> null
            is List<*> -> {
                @Suppress("UNCHECKED_CAST")
                v as List<Any?>
            }
            else -> throw ConfigBindException(path, "$path must be a list")
        }

    private fun Map<String, Any?>.reqString(
        key: String,
        path: String,
    ): String {
        val v = this[key] ?: throw ConfigBindException(path, "$path is required")
        return v as? String ?: throw ConfigBindException(path, "$path must be a string")
    }

    private fun Map<String, Any?>.optString(
        key: String,
        path: String,
    ): String? =
        when (val v = this[key]) {
            null -> null
            is String -> v
            else -> throw ConfigBindException(path, "$path must be a string")
        }

    private fun Map<String, Any?>.reqInt(
        key: String,
        path: String,
    ): Int {
        val v = this[key] ?: throw ConfigBindException(path, "$path is required")
        return v.toStrictInt(path)
    }

    private fun Map<String, Any?>.optInt(
        key: String,
        path: String,
    ): Int? =
        when (val v = this[key]) {
            null -> null
            else -> v.toStrictInt(path)
        }

    private fun Any?.toStrictInt(path: String): Int {
        val n = this as? Number ?: throw ConfigBindException(path, "$path must be a number")
        return when (n) {
            is Int -> n
            is Long -> n.toIntExact(path)
            is Short -> n.toInt()
            is Byte -> n.toInt()
            is Double -> n.toIntIfWhole(path)
            is Float -> n.toDouble().toIntIfWhole(path)
            else -> {
                val d = n.toDouble()
                d.toIntIfWhole(path)
            }
        }
    }

    private fun Long.toIntExact(path: String): Int {
        if (this < Int.MIN_VALUE.toLong() || this > Int.MAX_VALUE.toLong()) {
            throw ConfigBindException(path, "$path is out of Int range")
        }
        return toInt()
    }

    private fun Double.toIntIfWhole(path: String): Int {
        if (!isFinite()) throw ConfigBindException(path, "$path must be a finite number")
        if (this != floor(this)) throw ConfigBindException(path, "$path must be an integer")
        val asLong = toLong()
        return asLong.toIntExact(path)
    }

    private fun Map<String, Any?>.reqDouble(
        key: String,
        path: String,
    ): Double {
        val v = this[key] ?: throw ConfigBindException(path, "$path is required")
        val n = v as? Number ?: throw ConfigBindException(path, "$path must be a number")
        val d = n.toDouble()
        if (!d.isFinite()) throw ConfigBindException(path, "$path must be a finite number")
        return d
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

    private fun Map<String, Any?>.optStringList(
        key: String,
        path: String,
    ): List<String>? =
        when (val v = this[key]) {
            null -> null
            is List<*> ->
                v.mapIndexed { i, it ->
                    it as? String ?: throw ConfigBindException("$path[$i]", "$path[$i] must be a string")
                }
            else -> throw ConfigBindException(path, "$path must be a list of strings")
        }

    private fun Map<*, *>.toStringKeyMapOrdered(path: String): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>(this.size)
        for ((k, v) in this) {
            val ks = k as? String ?: throw ConfigBindException(path, "$path map key must be a string")
            out[ks] = v
        }
        return out
    }
}
