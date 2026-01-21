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
package io.shamash.asm.core

import io.shamash.asm.core.config.ConfigValidation
import io.shamash.asm.core.export.roles.RolesReader
import io.shamash.asm.core.scan.ScanOptions
import io.shamash.asm.core.scan.ShamashAsmScanRunner
import org.junit.Assume
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RolesExportIntegrationTest {
    @Test
    fun `roles json is exported and contains matched classes`() {
        val compiler = ToolProvider.getSystemJavaCompiler()
        Assume.assumeNotNull(compiler)

        val project = Files.createTempDirectory("shamash-roles-export")
        try {
            val outDir = project.resolve("build/classes/java/main")
            Files.createDirectories(outDir)
            compileJava(project, "com.example.App", "package com.example; public class App {}", outDir)

            val cfgDir = project.resolve("shamash/configs")
            Files.createDirectories(cfgDir)
            val cfgPath = cfgDir.resolve("asm.yml")
            Files.writeString(cfgPath, configYaml())

            val validation = Files.newBufferedReader(cfgPath).use { reader -> ConfigValidation.loadAndValidateV1(reader) }
            assertTrue(validation.ok, "config should validate: ${validation.errors}")
            assertNotNull(validation.config)

            val runner = ShamashAsmScanRunner()
            val result = runner.run(ScanOptions(projectBasePath = project, projectName = "demo"))

            assertTrue(result.configErrors.isEmpty(), "config errors should be empty: ${result.configErrors}")
            assertTrue(result.scanErrors.isEmpty(), "scan errors should be empty: ${result.scanErrors}")

            val engine = result.engine
            assertNotNull(engine)
            assertTrue(engine.isSuccess, "engine should succeed: ${engine.errors}")

            val export = engine.export
            assertNotNull(export)

            val rolesPath = export.rolesPath
            assertNotNull(rolesPath, "rolesPath must be present when enabled")
            assertTrue(rolesPath.exists() && rolesPath.isRegularFile(), "roles.json must exist")

            val doc = RolesReader.read(rolesPath)
            assertEquals("shamash-roles", doc.schemaId)

            val appRole = doc.roles.firstOrNull { it.id == "app" }
            assertNotNull(appRole)
            assertTrue(appRole.classes.any { it == "com.example.App" }, "role must include com.example.App")
            assertTrue(doc.totals.roles >= 1)
            assertTrue(doc.totals.classesMatched >= 1)
        } finally {
            project.toFile().deleteRecursively()
        }
    }

    private fun configYaml(): String =
        """
        version: 1

        project:
          bytecode:
            roots: ["build/classes/java/main"]

            outputsGlobs:
              include: ["**"]
              exclude: []

            jarGlobs:
              include: ["**/*.jar"]
              exclude: ["**/*"]

          scan:
            scope: PROJECT_ONLY
            followSymlinks: false
            maxClasses: null
            maxJarBytes: null
            maxClassBytes: null

          validation:
            unknownRule: IGNORE

        roles:
          app:
            priority: 10
            description: App
            match:
              packageRegex: '^com\.example(\.|$)'

        analysis:
          graphs:
            enabled: false
            granularity: PACKAGE
            includeExternalBuckets: false

          hotspots:
            enabled: false
            topN: 10
            includeExternal: false

          scoring:
            enabled: false
            model: V1
            godClass:
              enabled: false
              weights: null
              thresholds: null
            overall:
              enabled: false
              weights: null
              thresholds: null

        rules: []
        exceptions: []

        baseline:
          mode: NONE
          path: .shamash/baseline.json

        export:
          enabled: true
          outputDir: .shamash/reports/asm
          formats: [JSON]
          overwrite: true
          artifacts:
            roles:
              enabled: true
            facts:
              enabled: false
        """.trimIndent()

    private fun compileJava(
        tmp: Path,
        fqcn: String,
        source: String,
        outputDir: Path,
    ) {
        val compiler = ToolProvider.getSystemJavaCompiler() ?: error("JDK compiler not available")

        val parts = fqcn.split('.')
        val cls = parts.last()
        val pkgPath = parts.dropLast(1).joinToString("/")

        val srcDir = tmp.resolve("srcgen").resolve(pkgPath)
        Files.createDirectories(srcDir)

        val javaFile = srcDir.resolve("$cls.java")
        Files.writeString(javaFile, source)

        val rc = compiler.run(null, null, null, "-d", outputDir.toString(), javaFile.toString())
        if (rc != 0) error("javac failed with exit code $rc")
    }
}
