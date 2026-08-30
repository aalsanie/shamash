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

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MainTest {
    data class ProcResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    )

    @Test
    fun `version prints version and exits zero`() {
        val r = runCli("version")
        assertEquals(0, r.exitCode, diagnostics(r))
        assertTrue(r.stdout.trim().startsWith("shamash-cli"))
    }

    @Test
    fun `init stdout defaults to small starter config`() {
        val r = runCli("init", "--stdout")
        assertEquals(0, r.exitCode, diagnostics(r))
        assertTrue(r.stdout.contains("graph, name: noCycles"))
        assertFalse(r.stdout.contains("forbiddenRoleDependencies"))
        assertTrue(r.stdout.lineSequence().count() < 40, "starter config should remain intentionally small")
    }

    @Test
    fun `spring preset is explicit and contains boundary rule`() {
        val r = runCli("init", "--stdout", "--preset", "spring")
        assertEquals(0, r.exitCode, diagnostics(r))
        assertTrue(r.stdout.contains("forbiddenRoleDependencies"))
        assertTrue(r.stdout.contains("org.springframework.stereotype.Service"))
    }

    @Test
    fun `reference preset preserves advanced reference config`() {
        val r = runCli("init", "--stdout", "--preset", "reference")
        assertEquals(0, r.exitCode, diagnostics(r))
        assertTrue(r.stdout.contains("artifacts:"))
        assertTrue(r.stdout.contains("scoring:"))
    }

    @Test
    fun `init writes config and validate succeeds`(
        @TempDir tmp: Path,
    ) {
        val init = runCli("init", "--project", tmp.toString())
        assertEquals(0, init.exitCode, diagnostics(init))
        val config = tmp.resolve("shamash/configs/asm.yml")
        assertTrue(config.exists())
        assertTrue(config.readText(StandardCharsets.UTF_8).contains("noCycles"))
        val validate = runCli("validate", "--project", tmp.toString())
        assertEquals(0, validate.exitCode, diagnostics(validate))
    }

    @Test
    fun `init does not overwrite without force`(
        @TempDir tmp: Path,
    ) {
        assertEquals(0, runCli("init", "--project", tmp.toString()).exitCode)
        val config = tmp.resolve("shamash/configs/asm.yml")
        Files.writeString(config, "version: 1\n# mutated\n", StandardCharsets.UTF_8)
        val second = runCli("init", "--project", tmp.toString())
        assertEquals(2, second.exitCode)
        assertTrue(config.readText().contains("# mutated"))
        val forced = runCli("init", "--project", tmp.toString(), "--force")
        assertEquals(0, forced.exitCode, diagnostics(forced))
        assertFalse(config.readText().contains("# mutated"))
    }

    @Test
    fun `configless scan runs in discovery report-only mode`(
        @TempDir tmp: Path,
    ) {
        compileJava(tmp, "com.example.App", "package com.example; public class App {}")
        val r = runCli("scan", "--project", tmp.toString())
        assertEquals(0, r.exitCode, diagnostics(r))
        assertTrue(r.stdout.contains("Shamash - discovery scan"))
        assertTrue(r.stdout.contains("No project files were changed"))
        assertFalse(tmp.resolve("shamash").exists(), "discovery scan must not materialize config in the project")
        assertFalse(tmp.resolve(".shamash").exists(), "discovery scan must not write reports or baselines")
    }

    @Test
    fun `discovery refuses export facts so report-only mode cannot mutate project`(
        @TempDir tmp: Path,
    ) {
        compileJava(tmp, "com.example.App", "package com.example; public class App {}")
        val r = runCli("scan", "--project", tmp.toString(), "--export-facts")
        assertEquals(2, r.exitCode, diagnostics(r))
        assertTrue(r.stderr.contains("requires a project config"))
        assertFalse(tmp.resolve(".shamash").exists())
    }

    @Test
    fun `configless scan explains build requirement when no bytecode exists`(
        @TempDir tmp: Path,
    ) {
        Files.writeString(tmp.resolve("build.gradle.kts"), "plugins { java }", StandardCharsets.UTF_8)
        val r = runCli("scan", "--project", tmp.toString())
        assertEquals(2, r.exitCode, diagnostics(r))
        assertTrue(r.stderr.contains("No compiled JVM classes found"))
        assertTrue(r.stderr.contains("gradle classes"))
    }

    @Test
    fun `configured scan prints findings without print-findings flag`(
        @TempDir tmp: Path,
    ) {
        compileJava(
            tmp,
            "com.example.TooLarge",
            "package com.example; public class TooLarge {" + (1..61).joinToString("") { " public void m$it(){}" } + " }",
        )
        writeConfig(tmp, discoveryLikeConfig(maxMethods = 10))
        val r = runCli("scan", "--project", tmp.toString(), "--fail-on", "NONE")
        assertEquals(0, r.exitCode, diagnostics(r))
        assertTrue(r.stdout.contains("maxMethodsPerClass"))
        assertTrue(r.stdout.contains("Shamash found"))
    }

    @Test
    fun `baseline create writes accepted debt and protects existing baseline`(
        @TempDir tmp: Path,
    ) {
        compileJava(tmp, "com.example.App", "package com.example; public class App {}")
        runCli("init", "--project", tmp.toString())
        val first = runCli("baseline", "create", "--project", tmp.toString())
        assertEquals(0, first.exitCode, diagnostics(first))
        val baseline = tmp.resolve(".shamash/baseline/asm-baseline.json")
        assertTrue(baseline.exists())
        val second = runCli("baseline", "create", "--project", tmp.toString())
        assertEquals(2, second.exitCode, diagnostics(second))
        assertTrue(second.stderr.contains("already exists"))
    }

    @Test
    fun `baseline create switches generated reference config to verify automatically`(
        @TempDir tmp: Path,
    ) {
        compileJava(tmp, "com.example.App", "package com.example; public class App {}")
        val init = runCli("init", "--project", tmp.toString(), "--preset", "reference")
        assertEquals(0, init.exitCode, diagnostics(init))

        val config = tmp.resolve("shamash/configs/asm.yml")
        assertTrue(config.readText().contains("mode: GENERATE"))

        val baseline = runCli("baseline", "create", "--project", tmp.toString())
        assertEquals(0, baseline.exitCode, diagnostics(baseline))
        assertTrue(config.readText().contains("mode: VERIFY"))
        assertFalse(config.readText().contains("mode: GENERATE"))
    }

    @Test
    fun `validate with no config exits two`(
        @TempDir tmp: Path,
    ) {
        val r = runCli("validate", "--project", tmp.toString())
        assertEquals(2, r.exitCode)
    }

    @Test
    fun `scan with missing explicit config exits two`(
        @TempDir tmp: Path,
    ) {
        val r = runCli("scan", "--project", tmp.toString(), "--config", "missing.yml")
        assertEquals(2, r.exitCode, diagnostics(r))
        assertTrue(r.stderr.contains("CONFIG_READ"))
    }

    @Test
    fun `malformed config is a configuration error`(
        @TempDir tmp: Path,
    ) {
        val config = tmp.resolve("bad.yml")
        Files.writeString(config, "version: [", StandardCharsets.UTF_8)

        val scan = runCli("scan", "--project", tmp.toString(), "--config", config.fileName.toString())
        assertEquals(2, scan.exitCode, diagnostics(scan))

        val validate = runCli("validate", "--project", tmp.toString(), "--config", config.fileName.toString())
        assertEquals(2, validate.exitCode, diagnostics(validate))
    }

    @Test
    fun `invalid scan options fail before project work`() {
        val badFail = runCli("scan", "--fail-on", "BAD")
        assertEquals(2, badFail.exitCode)
        assertTrue(badFail.stderr.contains("Unknown fail-on severity"))

        val badScope = runCli("scan", "--scope", "BAD")
        assertEquals(2, badScope.exitCode)
        assertTrue(badScope.stderr.contains("Unknown --scope"))

        val badLimit = runCli("scan", "--max-classes", "0")
        assertEquals(2, badLimit.exitCode)
        assertTrue(badLimit.stderr.contains("Invalid --max-classes"))
    }

    @Test
    fun `registry list keeps built in provider discoverable`() {
        val r = runCli("registry", "list")
        assertEquals(0, r.exitCode, diagnostics(r))
        assertTrue((r.stdout + r.stderr).contains("default", ignoreCase = true))
    }

    @Test
    fun `scan with unknown registry exits two with actionable guidance`(
        @TempDir tmp: Path,
    ) {
        compileJava(tmp, "com.example.App", "package com.example; public class App {}")
        assertEquals(0, runCli("init", "--project", tmp.toString()).exitCode)

        val r = runCli("scan", "--project", tmp.toString(), "--registry", "does-not-exist")
        assertEquals(2, r.exitCode, diagnostics(r))
        val text = r.stderr + "\n" + r.stdout
        assertTrue(text.contains("Unknown registry", ignoreCase = true))
        assertTrue(text.contains("default", ignoreCase = true))
        assertTrue(text.contains("registry list", ignoreCase = true))
    }

    @Test
    fun `verbose scan reports applied runtime overrides`(
        @TempDir tmp: Path,
    ) {
        compileJava(tmp, "com.example.App", "package com.example; public class App {}")
        assertEquals(0, runCli("init", "--project", tmp.toString()).exitCode)

        val r =
            runCli(
                "scan",
                "--project",
                tmp.toString(),
                "--scope",
                "ALL_SOURCES",
                "--max-classes",
                "1",
                "--verbose",
                "--fail-on",
                "NONE",
            )
        assertEquals(0, r.exitCode, diagnostics(r))
        assertTrue(r.stdout.contains("Overrides"))
        assertTrue(r.stdout.contains("ALL_SOURCES") || r.stdout.contains("maxClasses=1"))
    }

    @Test
    fun `build detector uses wrappers when present and system tools otherwise`(
        @TempDir tmp: Path,
    ) {
        val windows = System.getProperty("os.name").lowercase().contains("win")
        val gradleWrapper = if (windows) "gradlew.bat" else "gradlew"
        val mavenWrapper = if (windows) "mvnw.cmd" else "mvnw"

        Files.writeString(tmp.resolve("build.gradle.kts"), "", StandardCharsets.UTF_8)
        assertEquals(BuildHint("Gradle", "gradle classes"), ProjectBuildDetector.detect(tmp))
        Files.delete(tmp.resolve("build.gradle.kts"))
        Files.writeString(tmp.resolve("settings.gradle.kts"), "", StandardCharsets.UTF_8)
        assertEquals(BuildHint("Gradle", "gradle classes"), ProjectBuildDetector.detect(tmp))
        Files.delete(tmp.resolve("settings.gradle.kts"))
        Files.writeString(tmp.resolve("build.gradle.kts"), "", StandardCharsets.UTF_8)
        Files.writeString(tmp.resolve(gradleWrapper), "", StandardCharsets.UTF_8)
        assertEquals(
            BuildHint("Gradle", if (windows) ".\\gradlew.bat classes" else "./gradlew classes"),
            ProjectBuildDetector.detect(tmp),
        )

        Files.delete(tmp.resolve(gradleWrapper))
        Files.delete(tmp.resolve("build.gradle.kts"))
        Files.writeString(tmp.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8)
        assertEquals(BuildHint("Maven", "mvn package"), ProjectBuildDetector.detect(tmp))
        Files.writeString(tmp.resolve(mavenWrapper), "", StandardCharsets.UTF_8)
        assertEquals(
            BuildHint("Maven", if (windows) ".\\mvnw.cmd package" else "./mvnw package"),
            ProjectBuildDetector.detect(tmp),
        )
    }

    private fun diagnostics(r: ProcResult) = "stderr:\n${r.stderr}\nstdout:\n${r.stdout}"

    private fun runCli(vararg args: String): ProcResult {
        val javaExe = resolveJavaExecutable()
        val cp = System.getProperty("java.class.path")
        val cmd = arrayListOf(javaExe.toString(), "-Dfile.encoding=UTF-8", "-cp", cp, "io.shamash.cli.MainKt").apply { addAll(args) }
        val process = ProcessBuilder(cmd).redirectErrorStream(false).start()
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val outThread =
            Thread { process.inputStream.use { it.copyTo(stdout) } }.apply {
                isDaemon = true
                start()
            }
        val errThread =
            Thread { process.errorStream.use { it.copyTo(stderr) } }.apply {
                isDaemon = true
                start()
            }
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("CLI timed out: ${args.joinToString(" ")}")
        }
        outThread.join(1000)
        errThread.join(1000)
        return ProcResult(process.exitValue(), stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8))
    }

    private fun resolveJavaExecutable(): Path {
        val bin = Path.of(System.getProperty("java.home"), "bin")
        val name = if (System.getProperty("os.name").lowercase().contains("win")) "java.exe" else "java"
        return bin.resolve(name)
    }

    private fun compileJava(
        tmp: Path,
        fqcn: String,
        source: String,
    ) {
        val compiler = javax.tools.ToolProvider.getSystemJavaCompiler() ?: error("JDK compiler unavailable")
        val output = tmp.resolve("build/classes/java/main")
        Files.createDirectories(output)
        val parts = fqcn.split('.')
        val file = tmp.resolve("srcgen/${parts.dropLast(1).joinToString("/")}/${parts.last()}.java")
        Files.createDirectories(file.parent)
        Files.writeString(file, source, StandardCharsets.UTF_8)
        assertEquals(0, compiler.run(null, null, null, "-d", output.toString(), file.toString()))
    }

    private fun writeConfig(
        tmp: Path,
        yaml: String,
    ) {
        val path = tmp.resolve("shamash/configs/asm.yml")
        Files.createDirectories(path.parent)
        Files.writeString(path, yaml, StandardCharsets.UTF_8)
    }

    private fun discoveryLikeConfig(maxMethods: Int): String =
        """
        version: 1
        project:
          bytecode:
            roots: ["."]
            outputsGlobs: { include: ["**/build/classes/**"], exclude: ["**/test/**"] }
            jarGlobs: { include: ["**/build/libs/*.jar"], exclude: [] }
          scan: { scope: PROJECT_ONLY, followSymlinks: false, maxClasses: 50000, maxJarBytes: null, maxClassBytes: null }
          validation: { unknownRule: ERROR }
        roles: {}
        analysis:
          graphs: { enabled: false, granularity: PACKAGE, includeExternalBuckets: false }
          hotspots: { enabled: false, topN: 10, includeExternal: false }
          scoring:
            enabled: false
            model: V1
            godClass: { enabled: false, weights: null, thresholds: null }
            overall: { enabled: false, weights: null, thresholds: null }
        rules:
          - { type: metrics, name: maxMethodsPerClass, roles: null, enabled: true, severity: WARNING, params: { maxMethods: $maxMethods } }
        exceptions: []
        baseline: { mode: NONE, path: ".shamash/baseline/asm-baseline.json" }
        export: { enabled: false, outputDir: ".shamash/out/asm", formats: [JSON], overwrite: true }
        """.trimIndent()
}
