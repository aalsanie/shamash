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
package io.shamash.asm.core.scan

import io.shamash.asm.core.config.ConfigValidation
import io.shamash.asm.core.config.ProjectLayout
import io.shamash.asm.core.config.schema.v1.model.ExportArtifactsConfig
import io.shamash.asm.core.config.schema.v1.model.ExportFactsArtifactConfig
import io.shamash.asm.core.config.schema.v1.model.ExportFormat
import io.shamash.asm.core.config.schema.v1.model.ShamashAsmConfigV1
import io.shamash.asm.core.engine.ShamashAsmEngine
import io.shamash.asm.core.facts.FactExtractor
import io.shamash.asm.core.scan.bytecode.BytecodeScanner
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile

/**
 * ASM scan runner.
 *
 * This is the orchestration entrypoint that wires:
 * - config load + schema + semantic validation (asm-core config)
 * - bytecode scanning (asm-core scan)
 * - facts extraction (asm-core facts)
 * - analysis execution + export/baseline handling (asm-core engine + shamash-export)
 */
class ShamashAsmScanRunner(
    private val engine: ShamashAsmEngine = ShamashAsmEngine(),
) {
    fun run(
        options: ScanOptions,
        overrides: RunOverrides? = null,
    ): ScanResult {
        // config discovery + validation
        val configPath =
            options.configPath
                ?: discoverConfig(options.projectBasePath)
                ?: return ScanResult(
                    options = options,
                    configPath = null,
                    scanErrors =
                        listOf(
                            ScanError.of(
                                phase = ScanError.Phase.CONFIG_DISCOVERY,
                                message =
                                    "ASM config not found under ${ProjectLayout.ASM_CONFIG_DIR} " +
                                        "(expected one of: ${ProjectLayout.ASM_CONFIG_CANDIDATES.joinToString()})",
                            ),
                        ),
                )

        val validation =
            try {
                Files.newBufferedReader(configPath, StandardCharsets.UTF_8).use { reader ->
                    ConfigValidation.loadAndValidateV1(reader, schemaValidator = options.schemaValidator)
                }
            } catch (t: Throwable) {
                return ScanResult(
                    options = options,
                    configPath = configPath,
                    scanErrors =
                        listOf(
                            ScanError.of(
                                phase = ScanError.Phase.CONFIG_READ,
                                message =
                                    "Failed to read/validate config: " +
                                        "${t.message ?: t::class.java.simpleName}",
                                path = configPath.toString(),
                                t = t,
                            ),
                        ),
                )
            }

        val config = validation.config
        if (!validation.ok || config == null) {
            return ScanResult(
                options = options,
                configPath = configPath,
                config = config,
                configErrors = validation.errors,
            )
        }

        // CLI/runner overrides (non-persistent): allow forcing facts export regardless of config.
        val effectiveConfig0: ShamashAsmConfigV1 =
            if (options.exportFacts) {
                forceEnableFactsExport(config, options)
            } else {
                config
            }

        val (effectiveConfig, appliedOverrides) = applyOverrides(effectiveConfig0, overrides)

        // scan bytecode
        val scan =
            try {
                BytecodeScanner().scan(
                    projectBasePath = options.projectBasePath,
                    bytecode = effectiveConfig.project.bytecode,
                    scan = effectiveConfig.project.scan,
                )
            } catch (t: Throwable) {
                return ScanResult(
                    options = options,
                    appliedOverrides = appliedOverrides,
                    configPath = configPath,
                    config = effectiveConfig,
                    scanErrors =
                        listOf(
                            ScanError.of(
                                phase = ScanError.Phase.BYTECODE_SCAN,
                                message = "Bytecode scan failed: ${t.message ?: t::class.java.simpleName}",
                                t = t,
                            ),
                        ),
                )
            }

        val runnerErrors =
            scan.errors.map {
                ScanError(
                    phase = ScanError.Phase.BYTECODE_SCAN,
                    message = it.message,
                    path = it.path,
                    throwableClass = it.throwableClass,
                )
            }

        // facts
        val factsResult =
            try {
                FactExtractor.extractAll(scan.units.asSequence())
            } catch (t: Throwable) {
                return ScanResult(
                    options = options,
                    appliedOverrides = appliedOverrides,
                    configPath = configPath,
                    config = effectiveConfig,
                    origins = scan.origins,
                    classUnits = scan.units.size,
                    truncated = scan.truncated,
                    scanErrors =
                        runnerErrors +
                            ScanError.of(
                                phase = ScanError.Phase.FACTS_EXTRACTION,
                                message = "Facts extraction failed: ${t.message ?: t::class.java.simpleName}",
                                t = t,
                            ),
                )
            }

        // --- engine
        val engineResult =
            try {
                engine.analyze(
                    projectBasePath = options.projectBasePath,
                    projectName = options.projectName,
                    config = effectiveConfig,
                    facts = factsResult.facts,
                    includeFactsInResult = options.includeFactsInResult,
                )
            } catch (t: Throwable) {
                return ScanResult(
                    options = options,
                    appliedOverrides = appliedOverrides,
                    configPath = configPath,
                    config = effectiveConfig,
                    origins = scan.origins,
                    classUnits = scan.units.size,
                    truncated = scan.truncated,
                    factsErrors = factsResult.errors,
                    scanErrors =
                        runnerErrors +
                            ScanError.of(
                                phase = ScanError.Phase.ENGINE,
                                message = "Engine crashed: ${t.message ?: t::class.java.simpleName}",
                                t = t,
                            ),
                )
            }

        return ScanResult(
            options = options,
            appliedOverrides = appliedOverrides,
            configPath = configPath,
            config = effectiveConfig,
            configErrors = validation.errors,
            scanErrors = runnerErrors,
            origins = scan.origins,
            classUnits = scan.units.size,
            truncated = scan.truncated,
            factsErrors = factsResult.errors,
            engine = engineResult,
        )
    }

    private fun applyOverrides(
        config: ShamashAsmConfigV1,
        overrides: RunOverrides?,
    ): Pair<ShamashAsmConfigV1, RunOverrides?> {
        val scanOv = overrides?.scan ?: return config to null

        val scan0 = config.project.scan
        val scan1 =
            scan0.copy(
                scope = scanOv.scope ?: scan0.scope,
                followSymlinks = scanOv.followSymlinks ?: scan0.followSymlinks,
                maxClasses = scanOv.maxClasses ?: scan0.maxClasses,
                maxJarBytes = scanOv.maxJarBytes ?: scan0.maxJarBytes,
                maxClassBytes = scanOv.maxClassBytes ?: scan0.maxClassBytes,
            )

        if (scan1 == scan0) return config to null

        val applied =
            ScanOverrides(
                scope = scanOv.scope?.takeIf { it != scan0.scope },
                followSymlinks = scanOv.followSymlinks?.takeIf { it != scan0.followSymlinks },
                maxClasses = scanOv.maxClasses?.takeIf { it != scan0.maxClasses },
                maxJarBytes = scanOv.maxJarBytes?.takeIf { it != scan0.maxJarBytes },
                maxClassBytes = scanOv.maxClassBytes?.takeIf { it != scan0.maxClassBytes },
            )

        val appliedRun =
            RunOverrides(
                scan =
                    applied.takeIf {
                        it.scope != null ||
                            it.followSymlinks != null ||
                            it.maxClasses != null ||
                            it.maxJarBytes != null ||
                            it.maxClassBytes != null
                    },
            )

        val next = config.copy(project = config.project.copy(scan = scan1))
        return next to appliedRun
    }

    private fun forceEnableFactsExport(
        config: ShamashAsmConfigV1,
        options: ScanOptions,
    ): ShamashAsmConfigV1 {
        val export0 = config.export
        val artifacts0 = export0.artifacts ?: ExportArtifactsConfig()

        val format = options.factsFormatOverride ?: artifacts0.facts?.format

        val factsCfg =
            (artifacts0.facts ?: ExportFactsArtifactConfig(enabled = true)).copy(
                enabled = true,
                format = format ?: ExportFactsArtifactConfig(enabled = true).format,
            )

        val artifacts = artifacts0.copy(facts = factsCfg)

        // Export must be enabled to write sidecars. If config disabled export, we enable it with safe defaults.
        val outputDir = export0.outputDir.trim().ifEmpty { ".shamash" }
        val formats = if (export0.formats.isNotEmpty()) export0.formats else listOf(ExportFormat.JSON)

        val export =
            export0.copy(
                enabled = true,
                outputDir = outputDir,
                formats = formats,
                artifacts = artifacts,
            )

        return config.copy(export = export)
    }

    private fun discoverConfig(projectBasePath: Path): Path? {
        for (candidate in ProjectLayout.ASM_CONFIG_CANDIDATES) {
            val p = projectBasePath.resolve(candidate)
            if (p.exists() && p.isRegularFile()) return p
        }
        return null
    }
}
