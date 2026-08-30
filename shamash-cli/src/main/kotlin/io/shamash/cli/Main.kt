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
package io.shamash.cli

import io.shamash.artifacts.contract.FindingSeverity
import io.shamash.artifacts.report.layout.ExportOutputLayout
import io.shamash.asm.core.config.ConfigValidation
import io.shamash.asm.core.config.ProjectLayout
import io.shamash.asm.core.config.ValidationSeverity
import io.shamash.asm.core.config.schema.v1.model.BaselineMode
import io.shamash.asm.core.config.schema.v1.model.ExportFactsFormat
import io.shamash.asm.core.config.schema.v1.model.ScanScope
import io.shamash.asm.core.engine.ShamashAsmEngine
import io.shamash.asm.core.engine.rules.DefaultRuleRegistry
import io.shamash.asm.core.engine.rules.spi.AsmRuleRegistryProvider
import io.shamash.asm.core.export.analysis.AnalysisSidecarReader
import io.shamash.asm.core.export.facts.FactsClassRecord
import io.shamash.asm.core.export.facts.FactsEdgeRecord
import io.shamash.asm.core.export.facts.FactsReader
import io.shamash.asm.core.scan.RunOverrides
import io.shamash.asm.core.scan.RunnerOverrides
import io.shamash.asm.core.scan.ScanOptions
import io.shamash.asm.core.scan.ScanOverrides
import io.shamash.asm.core.scan.ShamashAsmScanRunner
import io.shamash.cli.analysis.AnalysisCliFormatter
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.ExperimentalCli
import kotlinx.cli.Subcommand
import kotlinx.cli.default
import java.io.PrintWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.ServiceLoader
import kotlin.io.path.absolute
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.system.exitProcess

@OptIn(ExperimentalCli::class)
fun main(args: Array<String>) {
    val parser = ArgParser(programName = "shamash")
    val commands =
        listOf(
            InitCommand(),
            ValidateCommand(),
            ScanCommand(),
            BaselineCommand(),
            FactsCommand(),
            AnalysisCommand(),
            RegistryCommand(),
            VersionCommand(),
        )
    parser.subcommands(*commands.toTypedArray())

    try {
        parser.parse(args)
    } catch (t: Throwable) {
        Console.errln(t.message ?: t::class.java.simpleName)
        exitProcess(ExitCode.CONFIG_ERROR.code)
    }

    val invoked = commands.firstOrNull { it.wasInvoked }
    exitProcess((invoked?.exitCode ?: ExitCode.OK).code)
}

private object Console {
    private val out = PrintWriter(System.out, true)
    private val err = PrintWriter(System.err, true)

    fun println(line: String = "") = out.println(line)

    fun errln(line: String = "") = err.println(line)
}

private object CliMeta {
    val version: String = CliMeta::class.java.`package`?.implementationVersion ?: "dev"
}

private enum class ExitCode(
    val code: Int,
) {
    OK(0),
    CONFIG_ERROR(2),
    RUNTIME_ERROR(3),
    FINDINGS_THRESHOLD(4),
}

private enum class FailOn {
    NONE,
    INFO,
    WARNING,
    ERROR,
    ;

    fun shouldFail(findings: Map<FindingSeverity, Int>): Boolean {
        val error = findings[FindingSeverity.ERROR] ?: 0
        val warning = findings[FindingSeverity.WARNING] ?: 0
        val info = findings[FindingSeverity.INFO] ?: 0
        return when (this) {
            NONE -> false
            ERROR -> error > 0
            WARNING -> error > 0 || warning > 0
            INFO -> error > 0 || warning > 0 || info > 0
        }
    }

    companion object {
        fun parse(raw: String): FailOn =
            when (raw.trim().uppercase()) {
                "NONE" -> NONE
                "INFO" -> INFO
                "WARNING", "WARN" -> WARNING
                "ERROR", "ERR" -> ERROR
                else -> throw IllegalArgumentException("Unknown fail-on severity: '$raw' (expected: NONE|INFO|WARNING|ERROR)")
            }
    }
}

private abstract class CommandBase(
    name: String,
    actionDescription: String,
) : Subcommand(name, actionDescription) {
    var wasInvoked: Boolean = false
        private set
    var exitCode: ExitCode = ExitCode.OK
        protected set

    final override fun execute() {
        wasInvoked = true
        exitCode = run()
    }

    protected abstract fun run(): ExitCode
}

private class VersionCommand : CommandBase("version", "Print Shamash CLI version") {
    override fun run(): ExitCode {
        Console.println("shamash-cli ${CliMeta.version}")
        return ExitCode.OK
    }
}

private fun discoverConfig(projectRoot: Path): Path? =
    ProjectLayout.ASM_CONFIG_CANDIDATES
        .asSequence()
        .map { projectRoot.resolve(it).normalize() }
        .firstOrNull { it.exists() && it.isRegularFile() }

private fun loadResourceBytes(path: String): ByteArray? = ProjectLayout::class.java.getResourceAsStream(path)?.use { it.readBytes() }

private fun printNoBytecode(projectRoot: Path) {
    Console.errln("No compiled JVM classes found.")
    Console.errln()
    Console.errln("Shamash analyzes compiled Java/Kotlin code.")
    ProjectBuildDetector.detect(projectRoot)?.let {
        Console.errln("This looks like a ${it.tool} project. Build it first:")
        Console.errln()
        Console.errln("    ${it.command}")
        Console.errln()
        Console.errln("Then run:")
        Console.errln()
        Console.errln("    shamash scan")
    } ?: Console.errln("Build the project first, then run `shamash scan` again.")
}

private class InitCommand : CommandBase("init", "Create a small production-ready Shamash config") {
    private val project by option(ArgType.String, fullName = "project", description = "Project root").default(".")
    private val outPath: String? by option(ArgType.String, fullName = "path", description = "Output config path")
    private val force by option(ArgType.Boolean, fullName = "force", description = "Overwrite an existing config").default(false)
    private val stdout by option(ArgType.Boolean, fullName = "stdout", description = "Print config instead of writing it").default(false)
    private val preset by option(
        ArgType.String,
        fullName = "preset",
        description = "starter|spring|reference (default: starter)",
    ).default("starter")

    override fun run(): ExitCode {
        val projectRoot = Paths.get(project).normalize().absolute()
        val resource =
            when (preset.trim().lowercase()) {
                "starter", "default" -> {
                    ProjectLayout.STARTER_YML
                }

                "spring" -> {
                    ProjectLayout.SPRING_YML
                }

                "reference", "full" -> {
                    ProjectLayout.REFERENCE_YML
                }

                else -> {
                    Console.errln("Unknown preset '$preset' (expected: starter|spring|reference)")
                    return ExitCode.CONFIG_ERROR
                }
            }
        val bytes =
            loadResourceBytes(resource) ?: run {
                Console.errln("Embedded config resource not found: $resource")
                return ExitCode.RUNTIME_ERROR
            }
        if (stdout) {
            Console.println(String(bytes, StandardCharsets.UTF_8))
            return ExitCode.OK
        }

        val target = outPath?.let { projectRoot.resolve(it).normalize() } ?: projectRoot.resolve(ProjectLayout.ASM_CONFIG_RELATIVE_YML)
        return try {
            target.parent?.createDirectories()
            if (target.exists() && !force) {
                Console.errln("Config already exists: $target (use --force to overwrite)")
                ExitCode.CONFIG_ERROR
            } else {
                Files.write(target, bytes)
                Console.println("Created: $target")
                Console.println("Preset : ${preset.trim().lowercase()}")
                Console.println()
                Console.println("For an existing project, accept current debt once with:")
                Console.println("    shamash baseline create")
                ExitCode.OK
            }
        } catch (t: Throwable) {
            Console.errln("Failed to write config: ${t.message ?: t::class.java.simpleName}")
            ExitCode.RUNTIME_ERROR
        }
    }
}

private class ValidateCommand : CommandBase("validate", "Validate the project Shamash configuration") {
    private val project by option(ArgType.String, fullName = "project", description = "Project root").default(".")
    private val config: String? by option(ArgType.String, fullName = "config", description = "Explicit asm.yml path")

    override fun run(): ExitCode {
        val projectRoot = Paths.get(project).normalize().absolute()
        val configPath = config?.let { projectRoot.resolve(it).normalize() } ?: discoverConfig(projectRoot)
        if (configPath == null || !configPath.isRegularFile()) {
            Console.errln("ASM config not found under ${ProjectLayout.ASM_CONFIG_DIR}")
            return ExitCode.CONFIG_ERROR
        }
        val validation =
            try {
                Files.newBufferedReader(configPath, StandardCharsets.UTF_8).use { ConfigValidation.loadAndValidateV1(it) }
            } catch (t: Throwable) {
                Console.errln("Failed to read/validate config: ${t.message ?: t::class.java.simpleName}")
                return ExitCode.RUNTIME_ERROR
            }
        if (validation.errors.isEmpty()) {
            Console.println("OK: $configPath")
            return ExitCode.OK
        }
        Console.errln("Config issues: ${validation.errors.size}")
        validation.errors.forEach { Console.errln("- ${it.severity.name} ${it.path}: ${it.message}") }
        return if (validation.errors.any { it.severity == ValidationSeverity.ERROR }) ExitCode.CONFIG_ERROR else ExitCode.OK
    }
}

private object RegistryProviders {
    data class LoadResult(
        val providers: Map<String, AsmRuleRegistryProvider>,
        val errors: List<String>,
    )

    private val cached by lazy { loadInternal() }

    fun load(): LoadResult = cached

    private fun loadInternal(): LoadResult {
        val errors = mutableListOf<String>()
        val providers = linkedMapOf<String, AsmRuleRegistryProvider>()
        try {
            val iterator = ServiceLoader.load(AsmRuleRegistryProvider::class.java).iterator()
            while (iterator.hasNext()) {
                val provider =
                    try {
                        iterator.next()
                    } catch (t: Throwable) {
                        errors += "Failed to load registry provider: ${t.message ?: t::class.java.simpleName}"
                        continue
                    }
                val id = provider.id.trim()
                if (id.isEmpty()) {
                    errors += "Registry provider ${provider::class.qualifiedName} has empty id"
                } else if (providers.putIfAbsent(id, provider) != null) {
                    errors += "Duplicate registry provider id: $id"
                }
            }
        } catch (t: Throwable) {
            errors += "ServiceLoader error: ${t.message ?: t::class.java.simpleName}"
        }
        return LoadResult(providers, errors)
    }
}

private class RegistryCommand : CommandBase("registry", "List available advanced rule registries") {
    private val action by argument(ArgType.String, description = "Action (supported: list)")

    override fun run(): ExitCode {
        if (action.lowercase() != "list") {
            Console.errln("Unknown registry action: '$action' (supported: list)")
            return ExitCode.CONFIG_ERROR
        }
        val loaded = RegistryProviders.load()
        if (loaded.errors.isNotEmpty()) {
            loaded.errors.forEach { Console.errln("- $it") }
            return ExitCode.RUNTIME_ERROR
        }
        val providers = loaded.providers.values.sortedBy { it.id }
        if (providers.isEmpty()) {
            Console.println("No registry providers found on the classpath.")
            return ExitCode.OK
        }
        Console.println("ID       NAME                              IMPLEMENTATION")
        providers.forEach {
            Console.println("${it.id.padEnd(8)} ${(it.displayName.ifBlank { "(no displayName)" }).padEnd(33)} ${it::class.qualifiedName}")
        }
        return ExitCode.OK
    }
}

private class ScanCommand : CommandBase("scan", "Scan compiled JVM code for architecture risks and violations") {
    private val project by option(ArgType.String, fullName = "project", description = "Project root").default(".")
    private val config: String? by option(ArgType.String, fullName = "config", description = "Explicit asm.yml path")
    private val scopeRaw: String? by option(ArgType.String, fullName = "scope", description = "Override scan scope")
    private val followSymlinksRaw: String? by option(ArgType.String, fullName = "follow-symlinks", description = "true|false")
    private val maxClassesOverride: Int? by option(ArgType.Int, fullName = "max-classes", description = "Max classes (>0)")
    private val maxJarBytesOverride: Int? by option(ArgType.Int, fullName = "max-jar-bytes", description = "Max jar bytes (>0)")
    private val maxClassBytesOverride: Int? by option(ArgType.Int, fullName = "max-class-bytes", description = "Max class bytes (>0)")
    private val registryId: String? by option(ArgType.String, fullName = "registry", description = "Advanced rule registry id")
    private val includeFacts by option(ArgType.Boolean, fullName = "include-facts", description = "Keep FactIndex in memory").default(false)
    private val exportFacts by option(ArgType.Boolean, fullName = "export-facts", description = "Force facts export").default(false)
    private val factsFormatRaw: String? by option(ArgType.String, fullName = "facts-format", description = "JSON|JSONL_GZ")
    private val failOnRaw by option(ArgType.String, fullName = "fail-on", description = "NONE|INFO|WARNING|ERROR").default("ERROR")

    @Suppress("unused")
    private val printFindings by option(
        ArgType.Boolean,
        fullName = "print-findings",
        description = "Deprecated: findings are printed by default",
    ).default(false)
    private val allFindings by option(ArgType.Boolean, fullName = "all-findings", description = "Print every finding").default(false)
    private val verbose by option(ArgType.Boolean, fullName = "verbose", description = "Print engine and scan diagnostics").default(false)
    private val printAnalysisSummary by option(
        ArgType.Boolean,
        fullName = "print-analysis-summary",
        description = "Print graphs/hotspots/scoring summary",
    ).default(false)

    override fun run(): ExitCode {
        val projectRoot = Paths.get(project).normalize().absolute()
        val failOn =
            try {
                FailOn.parse(failOnRaw)
            } catch (t: Throwable) {
                Console.errln(t.message ?: "Invalid --fail-on")
                return ExitCode.CONFIG_ERROR
            }
        val scope =
            try {
                parseScanScopeOrNull(scopeRaw)
            } catch (t: Throwable) {
                Console.errln(t.message ?: "Invalid --scope")
                return ExitCode.CONFIG_ERROR
            }
        val follow =
            try {
                parseBoolOrNull(followSymlinksRaw, "--follow-symlinks")
            } catch (t: Throwable) {
                Console.errln(t.message ?: "Invalid --follow-symlinks")
                return ExitCode.CONFIG_ERROR
            }

        fun positive(
            value: Int?,
            name: String,
        ): Int? {
            if (value != null && value <= 0) throw IllegalArgumentException("Invalid $name: $value (must be > 0)")
            return value
        }
        val maxClasses: Int?
        val maxJarBytes: Int?
        val maxClassBytes: Int?
        try {
            maxClasses = positive(maxClassesOverride, "--max-classes")
            maxJarBytes = positive(maxJarBytesOverride, "--max-jar-bytes")
            maxClassBytes = positive(maxClassBytesOverride, "--max-class-bytes")
        } catch (t: Throwable) {
            Console.errln(t.message ?: "Invalid scan limit")
            return ExitCode.CONFIG_ERROR
        }
        val factsFormat =
            try {
                parseFactsFormatOrNull(factsFormatRaw, exportFacts)
            } catch (t: Throwable) {
                Console.errln(t.message ?: "Invalid --facts-format")
                return ExitCode.CONFIG_ERROR
            }

        val explicitConfig = config?.let { projectRoot.resolve(it).normalize() }
        val projectConfig = explicitConfig ?: discoverConfig(projectRoot)
        val discoveryMode = projectConfig == null
        if (discoveryMode && exportFacts) {
            Console.errln("--export-facts requires a project config. Run `shamash init` first.")
            return ExitCode.CONFIG_ERROR
        }
        var temporaryConfig: Path? = null
        val effectiveConfig =
            if (discoveryMode) {
                val bytes =
                    loadResourceBytes(ProjectLayout.DISCOVERY_YML) ?: run {
                        Console.errln("Embedded discovery configuration is missing.")
                        return ExitCode.RUNTIME_ERROR
                    }
                Files.createTempFile("shamash-discovery-", ".yml").also {
                    Files.write(it, bytes)
                    temporaryConfig = it
                }
            } else {
                projectConfig
            }

        try {
            val scanOverrides = ScanOverrides(scope, follow, maxClasses, maxJarBytes, maxClassBytes)
            val overrides =
                RunOverrides(scan = scanOverrides).takeIf {
                    listOf(scope, follow, maxClasses, maxJarBytes, maxClassBytes).any { value -> value != null }
                }
            val registry = resolveRegistry(registryId) ?: return ExitCode.CONFIG_ERROR
            val runner = ShamashAsmScanRunner(ShamashAsmEngine(registry = registry, toolName = "Shamash", toolVersion = CliMeta.version))
            val res =
                runner.run(
                    ScanOptions(
                        projectBasePath = projectRoot,
                        projectName = projectRoot.fileName?.toString() ?: "project",
                        configPath = effectiveConfig,
                        includeFactsInResult = includeFacts,
                        exportFacts = exportFacts,
                        factsFormatOverride = factsFormat,
                    ),
                    overrides,
                )

            if (res.configErrors.isNotEmpty()) {
                Console.errln("Config issues: ${res.configErrors.size}")
                res.configErrors.forEach { Console.errln("- ${it.severity.name} ${it.path}: ${it.message}") }
                return ExitCode.CONFIG_ERROR
            }
            if (res.scanErrors.isNotEmpty()) {
                Console.errln("Scan errors: ${res.scanErrors.size}")
                res.scanErrors.forEach { Console.errln("- ${it.phase.name}: ${it.message}${it.path?.let { p -> " [$p]" } ?: ""}") }
                return ExitCode.RUNTIME_ERROR
            }
            if (res.classUnits == 0) {
                printNoBytecode(projectRoot)
                return ExitCode.CONFIG_ERROR
            }
            if (res.factsErrors.isNotEmpty() && verbose) {
                res.factsErrors.forEach { Console.errln("Facts warning: ${it.originId} :: ${it.phase}: ${it.message}") }
            }
            val engine =
                res.engine ?: run {
                    Console.errln("Engine did not run.")
                    return ExitCode.RUNTIME_ERROR
                }
            if (engine.errors.isNotEmpty()) {
                engine.errors.forEach { Console.errln("Engine error: ${it.message}") }
                return ExitCode.RUNTIME_ERROR
            }

            val findings = engine.findings
            val counts = findings.groupingBy { it.severity }.eachCount()
            if (discoveryMode) {
                Console.println("Shamash — discovery scan")
                Console.println("Report-only mode. No project files were changed.")
                Console.println()
            }
            if (findings.isEmpty()) {
                Console.println("No architecture issues found.")
            } else {
                Console.println("Shamash found ${findings.size} architecture issue${if (findings.size == 1) "" else "s"}")
                Console.println()
                val limit = if (allFindings || printFindings) findings.size else 20
                findings.take(limit).forEach { finding ->
                    val location = buildLocation(finding.filePath, finding.classFqn, finding.memberName)
                    Console.println("${finding.severity.toString().padEnd(7)} ${finding.ruleId}")
                    Console.println("        ${finding.message}")
                    if (location.isNotBlank()) Console.println("        $location")
                    Console.println()
                }
                if (findings.size > limit) {
                    Console.println("Showing $limit of ${findings.size} findings. Use --all-findings to print everything.")
                    Console.println()
                }
            }

            Console.println("${res.classUnits}${if (res.truncated) "+" else ""} classes scanned")
            Console.println(
                "${counts[FindingSeverity.ERROR] ?: 0} errors, " +
                    "${counts[FindingSeverity.WARNING] ?: 0} warnings, ${counts[FindingSeverity.INFO] ?: 0} info",
            )

            if (discoveryMode) {
                Console.println()
                Console.println("Ready to enforce architecture? Run: shamash init")
            }

            if (verbose) printVerbose(res, engine.summary)
            if (printAnalysisSummary) printAnalysis(engine)


            return if (discoveryMode) {
                ExitCode.OK
            } else if (failOn.shouldFail(counts)) {
                ExitCode.FINDINGS_THRESHOLD
            } else {
                ExitCode.OK
            }
        } finally {
            temporaryConfig?.let { runCatching { Files.deleteIfExists(it) } }
        }
    }

    private fun resolveRegistry(raw: String?): io.shamash.asm.core.engine.rules.RuleRegistry? {
        val id = raw?.trim()?.takeIf { it.isNotEmpty() }
        if (id == null || id == "default") return DefaultRuleRegistry.create()
        val loaded = RegistryProviders.load()
        if (loaded.errors.isNotEmpty()) {
            loaded.errors.forEach { Console.errln("Registry provider load error: $it") }
            return null
        }
        val provider = loaded.providers[id]
        if (provider == null) {
            Console.errln("Unknown registry id: '$id'")
            Console.errln("Available registry ids: ${loaded.providers.keys.sorted().joinToString().ifBlank { "default" }}")
            Console.errln("Run: shamash registry list")
            return null
        }
        return try {
            provider.create()
        } catch (t: Throwable) {
            Console.errln("Registry '$id' failed to initialize: ${t.message ?: t::class.java.simpleName}")
            null
        }
    }

    private fun printVerbose(
        res: io.shamash.asm.core.scan.ScanResult,
        summary: io.shamash.asm.core.engine.EngineRunSummary,
    ) {
        Console.println()
        Console.println("--- Diagnostics ---")
        Console.println("Project     : ${summary.projectName}")
        Console.println("Base path   : ${summary.projectBasePath}")
        Console.println("Config      : ${res.configPath}")
        Console.println(
            "Facts       : classes=${summary.factsStats.classes} methods=${summary.factsStats.methods} fields=${summary.factsStats.fields} edges=${summary.factsStats.edges}",
        )
        Console.println(
            "Rules       : configured=${summary.ruleStats.configuredRules} executed=${summary.ruleStats.executedRules} skipped=${summary.ruleStats.skippedRules}",
        )
        res.appliedOverrides?.scan?.let { Console.println("Overrides   : $it") }
        res.appliedOverrides?.runner?.let { Console.println("Run override: $it") }
        Console.println("Export dir  : ${res.engine?.export?.outputDir ?: "(disabled)"}")
    }

    private fun printAnalysis(engine: io.shamash.asm.core.engine.EngineResult) {
        val export = engine.export
        val fromExport =
            export?.let {
                AnalysisSidecarReader.readAll(
                    graphsPath = it.analysisGraphsPath,
                    hotspotsPath = it.analysisHotspotsPath,
                    scoresPath = it.analysisScoresPath,
                )
            }
        val analysis = engine.analysis ?: fromExport
        Console.println()
        if (analysis == null || analysis.isEmpty) {
            Console.println("Analysis: (none)")
        } else {
            AnalysisCliFormatter.summaryLines(analysis, topCycles = 5, topHotspots = 5, topScores = 5).forEach { Console.println(it) }
        }
    }
}

private class BaselineCommand : CommandBase("baseline", "Manage accepted architecture debt") {
    private val action by argument(ArgType.String, description = "Action (supported: create)")
    private val project by option(ArgType.String, fullName = "project", description = "Project root").default(".")
    private val config: String? by option(ArgType.String, fullName = "config", description = "Explicit asm.yml path")
    private val force by option(ArgType.Boolean, fullName = "force", description = "Replace an existing baseline").default(false)

    override fun run(): ExitCode {
        if (action.trim().lowercase() != "create") {
            Console.errln("Unknown baseline action: '$action' (supported: create)")
            return ExitCode.CONFIG_ERROR
        }
        val projectRoot = Paths.get(project).normalize().absolute()
        val configPath = config?.let { projectRoot.resolve(it).normalize() } ?: discoverConfig(projectRoot)
        if (configPath == null || !configPath.isRegularFile()) {
            Console.errln("No Shamash config found. Run `shamash init` first.")
            return ExitCode.CONFIG_ERROR
        }
        val validation =
            try {
                Files.newBufferedReader(configPath, StandardCharsets.UTF_8).use { ConfigValidation.loadAndValidateV1(it) }
            } catch (t: Throwable) {
                Console.errln("Failed to read config: ${t.message ?: t::class.java.simpleName}")
                return ExitCode.RUNTIME_ERROR
            }
        val cfg = validation.config
        if (!validation.ok || cfg == null) {
            validation.errors.forEach { Console.errln("- ${it.severity.name} ${it.path}: ${it.message}") }
            return ExitCode.CONFIG_ERROR
        }
        val configEdit =
            try {
                BaselineConfigEditPlan.prepare(configPath, cfg.baseline.mode)
            } catch (t: Throwable) {
                Console.errln(t.message ?: "Unable to prepare baseline configuration update")
                return ExitCode.CONFIG_ERROR
            }
        val baselinePath = resolveBaselinePath(projectRoot, cfg.baseline.path)
        if (baselinePath.exists() && !force) {
            Console.errln("Baseline already exists: $baselinePath")
            Console.errln("Use --force to replace it.")
            return ExitCode.CONFIG_ERROR
        }

        val runner =
            ShamashAsmScanRunner(
                ShamashAsmEngine(registry = DefaultRuleRegistry.create(), toolName = "Shamash", toolVersion = CliMeta.version),
            )
        val res =
            runner.run(
                ScanOptions(projectBasePath = projectRoot, configPath = configPath),
                RunOverrides(runner = RunnerOverrides(baselineMode = BaselineMode.GENERATE, exportEnabled = false)),
            )
        if (res.configErrors.isNotEmpty()) {
            res.configErrors.forEach { Console.errln("- ${it.severity.name} ${it.path}: ${it.message}") }
            return ExitCode.CONFIG_ERROR
        }
        if (res.scanErrors.isNotEmpty()) {
            res.scanErrors.forEach { Console.errln("- ${it.phase}: ${it.message}") }
            return ExitCode.RUNTIME_ERROR
        }
        if (res.classUnits == 0) {
            printNoBytecode(projectRoot)
            return ExitCode.CONFIG_ERROR
        }
        val engine = res.engine ?: return ExitCode.RUNTIME_ERROR.also { Console.errln("Engine did not run.") }
        if (engine.errors.isNotEmpty()) {
            engine.errors.forEach { Console.errln("- ${it.message}") }
            return ExitCode.RUNTIME_ERROR
        }
        if (!baselinePath.isRegularFile()) {
            Console.errln("Baseline generation completed but no baseline file was written at: $baselinePath")
            return ExitCode.RUNTIME_ERROR
        }
        try {
            configEdit.apply()
        } catch (t: Throwable) {
            Console.errln("Baseline was created, but the config could not be switched to VERIFY: ${t.message ?: t::class.java.simpleName}")
            return ExitCode.RUNTIME_ERROR
        }
        val counts = engine.findings.groupingBy { it.severity }.eachCount()
        Console.println("Analyzed ${res.classUnits} classes.")
        Console.println()
        Console.println("Existing architecture debt:")
        Console.println("  ${counts[FindingSeverity.ERROR] ?: 0} errors")
        Console.println("  ${counts[FindingSeverity.WARNING] ?: 0} warnings")
        Console.println("  ${counts[FindingSeverity.INFO] ?: 0} info")
        Console.println()
        Console.println("Baseline created:")
        Console.println("  $baselinePath")
        Console.println()
        Console.println("Config baseline mode: VERIFY")
        Console.println("Future scans will report only new violations relative to this baseline.")
        Console.println("Commit the config and baseline together.")
        return ExitCode.OK
    }
}

private fun resolveBaselinePath(
    projectRoot: Path,
    raw: String,
): Path {
    val p =
        Paths
            .get(raw)
            .let { if (it.isAbsolute) it else projectRoot.resolve(it) }
            .normalize()
            .absolute()
    return if (p.toString().endsWith(".json")) p else p.resolve("baseline.json").normalize()
}

private fun buildLocation(
    filePath: String,
    classFqn: String?,
    memberName: String?,
): String =
    buildString {
        if (filePath.isNotBlank()) append(filePath)
        if (!classFqn.isNullOrBlank()) {
            if (isNotEmpty()) append(" :: ")
            append(classFqn)
            if (!memberName.isNullOrBlank()) append('#').append(memberName)
        }
    }

private fun parseFactsFormatOrNull(
    raw: String?,
    enabled: Boolean,
): ExportFactsFormat? {
    if (!enabled) return null
    return when (val value = raw?.trim()?.takeIf { it.isNotEmpty() }?.uppercase() ?: return null) {
        "JSON" -> ExportFactsFormat.JSON
        "JSONL_GZ", "JSONL", "JSONL.GZ" -> ExportFactsFormat.JSONL_GZ
        else -> throw IllegalArgumentException("Unknown --facts-format: '$value' (expected: JSON|JSONL_GZ)")
    }
}

private fun parseScanScopeOrNull(raw: String?): ScanScope? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() }?.uppercase() ?: return null
    return when (value) {
        "PROJECT_ONLY" -> ScanScope.PROJECT_ONLY

        "ALL_SOURCES" -> ScanScope.ALL_SOURCES

        "PROJECT_WITH_EXTERNAL_BUCKETS" -> ScanScope.PROJECT_WITH_EXTERNAL_BUCKETS

        else -> throw IllegalArgumentException(
            "Unknown --scope: '$value' (expected: PROJECT_ONLY|ALL_SOURCES|PROJECT_WITH_EXTERNAL_BUCKETS)",
        )
    }
}

private fun parseBoolOrNull(
    raw: String?,
    optionName: String,
): Boolean? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() }?.lowercase() ?: return null
    return when (value) {
        "true" -> true
        "false" -> false
        else -> throw IllegalArgumentException("Unknown $optionName: '$value' (expected: true|false)")
    }
}

private class AnalysisCommand : CommandBase("analysis", "Print summaries from exported graph/hotspot/score artifacts") {
    private val dirRaw: String? by option(ArgType.String, fullName = "dir", description = "Export output directory")
    private val top by option(ArgType.Int, fullName = "top", description = "Entries per section").default(5)

    override fun run(): ExitCode {
        val projectBase = Paths.get(".").toAbsolutePath().normalize()
        val defaultOut = ExportOutputLayout.normalizeOutputDir(projectBasePath = projectBase, outputDir = null)
        val dir = (dirRaw?.let { Paths.get(it) } ?: defaultOut).toAbsolutePath().normalize()
        val graphs = dir.resolve(ExportOutputLayout.ANALYSIS_GRAPHS_JSON_FILE_NAME)
        val hotspots = dir.resolve(ExportOutputLayout.ANALYSIS_HOTSPOTS_JSON_FILE_NAME)
        val scores = dir.resolve(ExportOutputLayout.ANALYSIS_SCORES_JSON_FILE_NAME)
        if (!Files.exists(graphs) && !Files.exists(hotspots) && !Files.exists(scores)) {
            Console.errln("No analysis artifacts found under: $dir")
            return ExitCode.RUNTIME_ERROR
        }
        AnalysisCliFormatter
            .summaryLines(
                AnalysisSidecarReader.readAll(graphs, hotspots, scores),
                top,
                top,
                top,
            ).forEach { Console.println(it) }
        return ExitCode.OK
    }
}

private fun packageOf(fqn: String): String = fqn.lastIndexOf('.').let { if (it <= 0) "" else fqn.substring(0, it) }

private class BoundedCounter(
    private val maxKeys: Int,
) {
    private val counts = HashMap<String, Long>()
    var droppedIncrements: Long = 0
        private set

    fun increment(
        key: String,
        delta: Long = 1L,
    ) {
        if (key.isEmpty()) return
        val current = counts[key]
        if (current != null) {
            counts[key] = current + delta
        } else if (counts.size >= maxKeys) {
            droppedIncrements += delta
        } else {
            counts[key] = delta
        }
    }

    fun get(key: String): Long = counts[key] ?: 0L

    fun top(n: Int): List<Pair<String, Long>> =
        counts.entries
            .asSequence()
            .map { it.key to it.value }
            .sortedWith(compareByDescending<Pair<String, Long>> { it.second }.thenBy { it.first })
            .take(n.coerceAtLeast(0))
            .toList()
}

private class FactsCommand : CommandBase("facts", "Inspect exported facts") {
    private val path: String? by option(ArgType.String, fullName = "path", description = "facts.jsonl.gz or facts.json")
    private val classFqn: String? by option(ArgType.String, fullName = "class", description = "Class FQN")
    private val packagePrefix: String? by option(ArgType.String, fullName = "package", description = "Package prefix")
    private val edgeFrom: String? by option(ArgType.String, fullName = "edge-from", description = "Edge source FQN")
    private val edgeTo: String? by option(ArgType.String, fullName = "edge-to", description = "Edge target FQN")
    private val top by option(ArgType.Int, fullName = "top", description = "Top N").default(20)
    private val maxKeys by option(ArgType.Int, fullName = "max-keys", description = "Counter key cap").default(200_000)

    override fun run(): ExitCode {
        val raw = path?.trim().orEmpty()
        if (raw.isEmpty()) {
            Console.errln("Missing --path")
            return ExitCode.CONFIG_ERROR
        }
        val p = Paths.get(raw).normalize().absolute()
        if (!p.isRegularFile()) {
            Console.errln("Facts file not found: $p")
            return ExitCode.CONFIG_ERROR
        }
        if (maxKeys <= 0 || top < 0) {
            Console.errln("--max-keys must be > 0 and --top must be >= 0")
            return ExitCode.CONFIG_ERROR
        }

        val target = classFqn?.trim()?.takeIf { it.isNotEmpty() }
        val pkgPrefix = packagePrefix?.trim()?.takeIf { it.isNotEmpty() }
        val fromFilter = edgeFrom?.trim()?.takeIf { it.isNotEmpty() }
        val toFilter = edgeTo?.trim()?.takeIf { it.isNotEmpty() }
        val wantEdges = target != null || pkgPrefix != null || fromFilter != null || toFilter != null

        var projectName: String? = null
        var tool: String? = null
        var toolVersion: String? = null
        var classes = 0
        var edges = 0
        var methods = 0L
        var fields = 0L
        var targetClass: FactsClassRecord? = null
        val pkgClasses = BoundedCounter(maxKeys)
        val pkgEdgesFrom = BoundedCounter(maxKeys)
        val fanOut = BoundedCounter(maxKeys)
        val fanIn = BoundedCounter(maxKeys)

        fun matches(e: FactsEdgeRecord): Boolean {
            if (fromFilter != null && e.from != fromFilter) return false
            if (toFilter != null && e.to != toFilter) return false
            if (target != null && e.from != target && e.to != target) return false
            if (pkgPrefix != null && !packageOf(e.from).startsWith(pkgPrefix) && !packageOf(e.to).startsWith(pkgPrefix)) return false
            return true
        }

        try {
            FactsReader.read(
                path = p,
                onMeta = {
                    projectName = it.projectName
                    tool = it.toolName
                    toolVersion = it.toolVersion
                },
                onClass = {
                    classes++
                    methods += it.methodCount
                    fields += it.fieldCount
                    pkgClasses.increment(it.packageName)
                    if (target != null && it.fqName == target) targetClass = it
                },
                onEdge = {
                    edges++
                    fanOut.increment(it.from)
                    fanIn.increment(it.to)
                    pkgEdgesFrom.increment(packageOf(it.from))
                    if (wantEdges &&
                        matches(it)
                    ) {
                        Console.println("${it.from} -> ${it.to} [${it.kind}${it.detail?.let { d -> ":$d" } ?: ""}]")
                    }
                },
            )
        } catch (t: Throwable) {
            Console.errln("Failed to read facts: ${t.message ?: t::class.java.simpleName}")
            return ExitCode.RUNTIME_ERROR
        }

        Console.println("Project     : ${projectName ?: "?"}")
        Console.println("Tool        : ${tool ?: "?"} ${toolVersion ?: ""}")
        Console.println("Classes     : $classes")
        Console.println("Methods     : $methods")
        Console.println("Fields      : $fields")
        Console.println("Edges       : $edges")
        Console.println()

        target?.let {
            val c = targetClass
            if (c == null) {
                Console.errln("Class not found in facts: $target")
            } else {
                Console.println("--- Class ---")
                Console.println("FQN         : ${c.fqName}")
                Console.println("Package     : ${c.packageName}")
                Console.println("Role        : ${c.role ?: ""}")
                Console.println("Visibility  : ${c.visibility}")
                Console.println("Methods     : ${c.methodCount}")
                Console.println("Fields      : ${c.fieldCount}")
                Console.println("Fan-out     : ${fanOut.get(c.fqName)}")
                Console.println("Fan-in      : ${fanIn.get(c.fqName)}")
                Console.println()
            }
        }

        fun printTop(
            title: String,
            counter: BoundedCounter,
        ) {
            Console.println("--- $title ---")
            counter.top(top).forEachIndexed { index, item -> Console.println("${index + 1}. ${item.first} = ${item.second}") }
            if (counter.droppedIncrements >
                0
            ) {
                Console.errln("$title: dropped increments due to --max-keys cap: ${counter.droppedIncrements}")
            }
            Console.println()
        }
        printTop("Top packages by classes", pkgClasses)
        printTop("Top packages by edges-from", pkgEdgesFrom)
        printTop("Top fan-out", fanOut)
        printTop("Top fan-in", fanIn)
        return ExitCode.OK
    }
}