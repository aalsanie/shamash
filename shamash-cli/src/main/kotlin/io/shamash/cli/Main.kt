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
import io.shamash.asm.core.config.ConfigValidation
import io.shamash.asm.core.config.ProjectLayout
import io.shamash.asm.core.config.ValidationSeverity
import io.shamash.asm.core.scan.ScanOptions
import io.shamash.asm.core.scan.ShamashAsmScanRunner
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.Subcommand
import kotlinx.cli.default
import java.io.PrintWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.absolute
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.system.exitProcess

/**
 * Shamash CLI.
 *
 * v1 goal: CI-friendly ASM runner.
 */
fun main(args: Array<String>) {
    val parser = ArgParser(programName = "shamash")

    val cmdInit = InitCommand()
    val cmdValidate = ValidateCommand()
    val cmdScan = ScanCommand()
    val cmdVersion = VersionCommand()

    parser.subcommands(cmdInit, cmdValidate, cmdScan, cmdVersion)

    try {
        parser.parse(args)
        // when no command is provided, kotlinx-cli will print help and not throw.
    } catch (t: Throwable) {
        Console.errln("${t.message ?: t::class.java.simpleName}")
        exitProcess(ExitCode.RUNTIME_ERROR.code)
    }

    val exit =
        when {
            cmdInit.wasInvoked -> cmdInit.exitCode
            cmdValidate.wasInvoked -> cmdValidate.exitCode
            cmdScan.wasInvoked -> cmdScan.exitCode
            cmdVersion.wasInvoked -> cmdVersion.exitCode
            else -> ExitCode.OK
        }

    exitProcess(exit.code)
}

private object Console {
    private val out = PrintWriter(System.out, true)
    private val err = PrintWriter(System.err, true)

    fun println(line: String = "") {
        out.println(line)
    }

    fun errln(line: String = "") {
        err.println(line)
    }
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
        if (this == NONE) return false

        val error = findings[FindingSeverity.ERROR] ?: 0
        val warn = findings[FindingSeverity.WARNING] ?: 0
        val info = findings[FindingSeverity.INFO] ?: 0

        return when (this) {
            ERROR -> error > 0
            WARNING -> error > 0 || warn > 0
            INFO -> error > 0 || warn > 0 || info > 0
            NONE -> false
        }
    }

    companion object {
        fun parse(raw: String): FailOn {
            val v = raw.trim().uppercase()
            return when (v) {
                "NONE" -> NONE
                "INFO" -> INFO
                "WARNING", "WARN" -> WARNING
                "ERROR", "ERR" -> ERROR
                else -> throw IllegalArgumentException("Unknown fail-on severity: '$raw' (expected: NONE|INFO|WARNING|ERROR)")
            }
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
        val version = this::class.java.`package`?.implementationVersion ?: "dev"
        Console.println("shamash-cli $version")
        return ExitCode.OK
    }
}

private class InitCommand : CommandBase("init", "Create an asm.yml config from the embedded reference") {
    private val project by option(
        type = ArgType.String,
        fullName = "project",
        description = "Project root (default: current directory)",
    ).default(".")

    private val outPath: String? by option(
        type = ArgType.String,
        fullName = "path",
        description = "Output path for the config (default: shamash/configs/asm.yml under project)",
    )

    private val force by option(
        type = ArgType.Boolean,
        fullName = "force",
        description = "Overwrite existing file",
    ).default(false)

    private val stdout by option(
        type = ArgType.Boolean,
        fullName = "stdout",
        description = "Print the reference config to stdout instead of writing a file",
    ).default(false)

    override fun run(): ExitCode {
        val projectRoot = Paths.get(project).normalize().absolute()
        val target =
            outPath?.let { projectRoot.resolve(it).normalize() }
                ?: projectRoot.resolve(ProjectLayout.ASM_CONFIG_RELATIVE_YML)

        val bytes = loadReferenceYaml() ?: return ExitCode.RUNTIME_ERROR

        if (stdout) {
            Console.println(String(bytes, StandardCharsets.UTF_8))
            return ExitCode.OK
        }

        try {
            target.parent?.createDirectories()
            if (target.exists() && !force) {
                Console.errln("Config already exists: $target (use --force to overwrite)")
                return ExitCode.CONFIG_ERROR
            }
            Files.write(target, bytes)
            Console.println("Wrote: $target")
            return ExitCode.OK
        } catch (t: Throwable) {
            Console.errln("Failed to write config: ${t.message ?: t::class.java.simpleName}")
            return ExitCode.RUNTIME_ERROR
        }
    }

    private fun loadReferenceYaml(): ByteArray? {
        val stream = ProjectLayout::class.java.getResourceAsStream(ProjectLayout.REFERENCE_YML)
        if (stream == null) {
            Console.errln("Embedded reference not found on classpath: ${ProjectLayout.REFERENCE_YML}")
            return null
        }
        return stream.use { it.readBytes() }
    }
}

private class ValidateCommand : CommandBase("validate", "Validate asm.yml against schema + semantic rules") {
    private val project by option(
        type = ArgType.String,
        fullName = "project",
        description = "Project root (default: current directory)",
    ).default(".")

    private val config: String? by option(
        type = ArgType.String,
        fullName = "config",
        description = "Explicit path to asm.yml (if not provided, discovery is used)",
    )

    override fun run(): ExitCode {
        val projectRoot = Paths.get(project).normalize().absolute()
        val configPath =
            config?.let { projectRoot.resolve(it).normalize() }
                ?: discoverConfig(projectRoot)
                ?: run {
                    Console.errln(
                        "ASM config not found under ${ProjectLayout.ASM_CONFIG_DIR} " +
                            "(expected one of: ${ProjectLayout.ASM_CONFIG_CANDIDATES.joinToString()})",
                    )
                    return ExitCode.CONFIG_ERROR
                }

        if (!configPath.exists() || !configPath.isRegularFile()) {
            Console.errln("Config file not found: $configPath")
            return ExitCode.CONFIG_ERROR
        }

        val validation =
            try {
                Files.newBufferedReader(configPath, StandardCharsets.UTF_8).use { reader ->
                    ConfigValidation.loadAndValidateV1(reader)
                }
            } catch (t: Throwable) {
                Console.errln("Failed to read/validate config: ${t.message ?: t::class.java.simpleName}")
                return ExitCode.RUNTIME_ERROR
            }

        val errors = validation.errors
        if (errors.isEmpty()) {
            Console.println("OK: $configPath")
            return ExitCode.OK
        }

        Console.errln("Config issues: ${errors.size}")
        for (e in errors) {
            val prefix = e.severity.name
            Console.errln("- $prefix ${e.path}: ${e.message}")
        }

        val hasError = errors.any { it.severity == ValidationSeverity.ERROR }
        return if (hasError) ExitCode.CONFIG_ERROR else ExitCode.OK
    }

    private fun discoverConfig(projectBasePath: Path): Path? {
        for (candidate in ProjectLayout.ASM_CONFIG_CANDIDATES) {
            val p = projectBasePath.resolve(candidate)
            if (p.exists() && p.isRegularFile()) return p
        }
        return null
    }
}

private class ScanCommand : CommandBase("scan", "Run ASM scan + analysis (CI-friendly)") {
    private val project by option(
        type = ArgType.String,
        fullName = "project",
        description = "Project root (default: current directory)",
    ).default(".")

    private val config: String? by option(
        type = ArgType.String,
        fullName = "config",
        description = "Explicit path to asm.yml (if not provided, discovery is used)",
    )

    private val includeFacts by option(
        type = ArgType.Boolean,
        fullName = "include-facts",
        description = "Include FactIndex in memory result (debug)",
    ).default(false)

    private val failOnRaw by option(
        type = ArgType.String,
        fullName = "fail-on",
        description = "Fail the process when findings include this severity or higher: NONE|INFO|WARNING|ERROR",
    ).default("ERROR")

    private val printFindings by option(
        type = ArgType.Boolean,
        fullName = "print-findings",
        description = "Print findings list (in addition to the summary)",
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

        val configPath = config?.let { projectRoot.resolve(it).normalize() }

        val runner = ShamashAsmScanRunner()
        val res =
            runner.run(
                ScanOptions(
                    projectBasePath = projectRoot,
                    projectName = projectRoot.fileName?.toString() ?: "project",
                    configPath = configPath,
                    includeFactsInResult = includeFacts,
                ),
            )

        // ----- config problems
        if (res.configErrors.isNotEmpty()) {
            Console.errln("Config issues: ${res.configErrors.size}")
            for (e in res.configErrors) {
                Console.errln("- ${e.severity.name} ${e.path}: ${e.message}")
            }
            return ExitCode.CONFIG_ERROR
        }
        if (res.configPath == null) {
            for (e in res.scanErrors) {
                Console.errln("${e.phase.name}: ${e.message}")
            }
            return ExitCode.CONFIG_ERROR
        }

        // ----- runner problems
        if (res.scanErrors.isNotEmpty()) {
            Console.errln("Scan errors: ${res.scanErrors.size}")
            for (e in res.scanErrors) {
                Console.errln("- ${e.phase.name}: ${e.message}${e.path?.let { " [$it]" } ?: ""}")
            }
            return ExitCode.RUNTIME_ERROR
        }
        if (res.factsErrors.isNotEmpty()) {
            Console.errln("Facts warnings: ${res.factsErrors.size}")
            for (e in res.factsErrors) {
                Console.errln("- ${e.originId} :: ${e.phase}: ${e.message}")
            }
        }

        val engine = res.engine
        if (engine == null) {
            Console.errln("Engine did not run.")
            return ExitCode.RUNTIME_ERROR
        }
        if (engine.errors.isNotEmpty()) {
            Console.errln("Engine errors: ${engine.errors.size}")
            for (e in engine.errors) {
                Console.errln("- ${e.message}")
            }
            // engine errors are runtime errors
            return ExitCode.RUNTIME_ERROR
        }

        // ----- summary
        val summary = engine.summary
        val findingCounts = engine.findings.groupingBy { it.severity }.eachCount()

        Console.println("Project     : ${summary.projectName}")
        Console.println("Base path   : ${summary.projectBasePath}")
        Console.println("Config      : ${res.configPath}")
        Console.println("Classes     : ${res.classUnits}${if (res.truncated) " (truncated)" else ""}")
        Console.println(
            "Facts       : classes=${summary.factsStats.classes} methods=${summary.factsStats.methods} " +
                "fields=${summary.factsStats.fields} edges=${summary.factsStats.edges}",
        )
        Console.println(
            "Rules       : configured=${summary.ruleStats.configuredRules} executed=${summary.ruleStats.executedRules} " +
                "skipped=${summary.ruleStats.skippedRules}",
        )
        Console.println(
            "Rule Inst.  : executed=${summary.ruleStats.executedRuleInstances} skipped=${summary.ruleStats.skippedRuleInstances} " +
                "notFound=${summary.ruleStats.notFoundRuleInstances} failed=${summary.ruleStats.failedRuleInstances}",
        )
        Console.println(
            "Findings    : ERROR=${findingCounts[FindingSeverity.ERROR] ?: 0} " +
                "WARNING=${findingCounts[FindingSeverity.WARNING] ?: 0} " +
                "INFO=${findingCounts[FindingSeverity.INFO] ?: 0}",
        )

        val export = engine.export
        if (export != null) {
            Console.println("Export dir  : ${export.outputDir}")
            Console.println("Baseline    : ${if (export.baselineWritten) "written" else "no-change"}")
        } else {
            Console.println("Export dir  : (disabled)")
        }

        if (printFindings && engine.findings.isNotEmpty()) {
            Console.println()
            Console.println("--- Findings (${engine.findings.size}) ---")
            for ((i, f) in engine.findings.withIndex()) {
                val where =
                    buildString {
                        append(f.filePath)
                        if (!f.classFqn.isNullOrBlank()) {
                            append(" :: ")
                            append(f.classFqn)
                            if (!f.memberName.isNullOrBlank()) {
                                append('#')
                                append(f.memberName)
                            }
                        }
                        if (f.startOffset != null || f.endOffset != null) {
                            append(" [")
                            append(f.startOffset ?: "?")
                            append("..")
                            append(f.endOffset ?: "?")
                            append(']')
                        }
                    }
                Console.println("${i + 1}. ${f.severity} ${f.ruleId}: ${f.message} ($where)")
            }
        }

        // ----- CI fail threshold
        return if (failOn.shouldFail(findingCounts)) ExitCode.FINDINGS_THRESHOLD else ExitCode.OK
    }
}
