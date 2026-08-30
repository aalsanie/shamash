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

class ShamashAsmScanRunner(
    private val engine: ShamashAsmEngine = ShamashAsmEngine(),
) {
    fun run(
        options: ScanOptions,
        overrides: RunOverrides? = null,
    ): ScanResult {
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
                                message = "Failed to read/validate config: ${t.message ?: t::class.java.simpleName}",
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

        val effectiveConfig0 = if (options.exportFacts) forceEnableFactsExport(config, options) else config
        val (effectiveConfig, appliedOverrides) = applyOverrides(effectiveConfig0, overrides)

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

        if (scan.units.isEmpty()) {
            return ScanResult(
                options = options,
                appliedOverrides = appliedOverrides,
                configPath = configPath,
                config = effectiveConfig,
                configErrors = validation.errors,
                scanErrors = runnerErrors,
                origins = scan.origins,
                classUnits = 0,
                truncated = scan.truncated,
            )
        }

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
        if (overrides == null) return config to null

        val scan0 = config.project.scan
        val scanOv = overrides.scan
        val scan1 =
            if (scanOv == null) {
                scan0
            } else {
                scan0.copy(
                    scope = scanOv.scope ?: scan0.scope,
                    followSymlinks = scanOv.followSymlinks ?: scan0.followSymlinks,
                    maxClasses = scanOv.maxClasses ?: scan0.maxClasses,
                    maxJarBytes = scanOv.maxJarBytes ?: scan0.maxJarBytes,
                    maxClassBytes = scanOv.maxClassBytes ?: scan0.maxClassBytes,
                )
            }

        val runnerOv = overrides.runner
        val baseline1 = config.baseline.copy(mode = runnerOv?.baselineMode ?: config.baseline.mode)
        val export1 = config.export.copy(enabled = runnerOv?.exportEnabled ?: config.export.enabled)

        val appliedScan =
            scanOv?.let {
                ScanOverrides(
                    scope = it.scope?.takeIf { v -> v != scan0.scope },
                    followSymlinks = it.followSymlinks?.takeIf { v -> v != scan0.followSymlinks },
                    maxClasses = it.maxClasses?.takeIf { v -> v != scan0.maxClasses },
                    maxJarBytes = it.maxJarBytes?.takeIf { v -> v != scan0.maxJarBytes },
                    maxClassBytes = it.maxClassBytes?.takeIf { v -> v != scan0.maxClassBytes },
                ).takeIf { a ->
                    a.scope != null || a.followSymlinks != null || a.maxClasses != null ||
                        a.maxJarBytes != null || a.maxClassBytes != null
                }
            }

        val appliedRunner =
            runnerOv?.let {
                RunnerOverrides(
                    baselineMode = it.baselineMode?.takeIf { v -> v != config.baseline.mode },
                    exportEnabled = it.exportEnabled?.takeIf { v -> v != config.export.enabled },
                ).takeIf { a -> a.baselineMode != null || a.exportEnabled != null }
            }

        val next =
            config.copy(
                project = config.project.copy(scan = scan1),
                baseline = baseline1,
                export = export1,
            )

        val applied = RunOverrides(scan = appliedScan, runner = appliedRunner).takeIf { appliedScan != null || appliedRunner != null }
        return next to applied
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
        val outputDir = export0.outputDir.trim().ifEmpty { ".shamash" }
        val formats = if (export0.formats.isNotEmpty()) export0.formats else listOf(ExportFormat.JSON)
        val export = export0.copy(enabled = true, outputDir = outputDir, formats = formats, artifacts = artifacts)
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
