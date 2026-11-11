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
package io.shamash.intellij.plugin.journeys

import com.intellij.driver.client.Remote
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.singleProject
import com.intellij.driver.sdk.ui.components.ideFrame
import com.intellij.driver.sdk.ui.present
import com.intellij.driver.sdk.ui.shouldBe
import com.intellij.driver.sdk.ui.xQuery
import com.intellij.driver.sdk.waitFor
import com.intellij.driver.sdk.waitForIndicators
import com.intellij.ide.starter.ci.CIServer
import com.intellij.ide.starter.ci.NoCIServer
import com.intellij.ide.starter.di.di
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.ide.starter.ide.IdeProductProvider
import com.intellij.ide.starter.models.TestCase
import com.intellij.ide.starter.plugins.PluginConfigurator
import com.intellij.ide.starter.project.LocalProjectInfo
import com.intellij.ide.starter.runner.Starter
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes

@Tag("benchmark")
class ShamashSourcePerformanceTest {
    @field:TempDir
    lateinit var testDirectory: Path

    init {
        di =
            DI {
                extend(di)
                bindSingleton<CIServer>(overrides = true) {
                    Proxy.newProxyInstance(
                        CIServer::class.java.classLoader,
                        arrayOf(CIServer::class.java),
                    ) { _, method, args ->
                        if (method.name == "reportTestFailure") {
                            throw AssertionError(args?.joinToString("\n") ?: "IDE reported an error")
                        }
                        try {
                            method.invoke(NoCIServer, *(args ?: emptyArray()))
                        } catch (failure: InvocationTargetException) {
                            throw failure.targetException
                        }
                    } as CIServer
                }
            }
    }

    @Test
    fun warmThousandClasses() = measure(1_000)

    @Test
    fun warmTenThousandClasses() = measure(10_000)

    private fun measure(classCount: Int) {
        val project = projectFixture(classCount)
        val version = System.getProperty("shamash.ide.version", "2024.2.1")
        val context =
            Starter
                .newContext(
                    testName = "shamash-perf-$classCount-${version.replace('.', '-')}",
                    TestCase(
                        if (version == "2024.2.1" || version == "2025.2") IdeProductProvider.IC else IdeProductProvider.IU,
                        LocalProjectInfo(project),
                    ).withVersion(version),
                ).apply {
                    val zip = Path.of(requireNotNull(System.getProperty("path.to.build.plugin")))
                    require(Files.isRegularFile(zip))
                    PluginConfigurator(this).installPluginFromPath(zip)
                    applyVMOptionsPatch {
                        addSystemProperty("idea.trust.all.projects", true)
                        addSystemProperty("ide.show.tips.on.startup.default.value", false)
                    }
                }

        val startImport = System.nanoTime()
        context.runIdeWithDriver().useDriverAndCloseIde {
            waitForIndicators(20.minutes)
            val startupAndIndexingMs = (System.nanoTime() - startImport) / 1_000_000
            val durations = ArrayList<Long>(5)
            var warmupMs = 0L
            repeat(6) { iteration ->
                val before =
                    withContext {
                        service(RemoteAnalysisCoordinator::class, singleProject()).state().getRequestGeneration()
                    }
                val start = System.nanoTime()
                ideFrame {
                    invokeAction("io.shamash.open")
                    x(xQuery { byAccessibleName("Analyze project source") }).click()
                }
                waitFor(10.minutes, errorMessage = "Warm source scan $iteration must complete") {
                    withContext {
                        val state = service(RemoteAnalysisCoordinator::class, singleProject()).state()
                        state.getRequestGeneration() == before + 1 && !state.getActive()
                    }
                }
                val elapsedMs = (System.nanoTime() - start) / 1_000_000
                val completeFiles =
                    withContext {
                        service(RemoteAnalysisCoordinator::class, singleProject()).state().getCurrentCompleteFileCount()
                    }
                assertTrue(
                    completeFiles == classCount,
                    "Warm source scan $iteration did not complete for all $classCount files (completed: $completeFiles)",
                )
                ideFrame {
                    x(xQuery { byVisibleText("No package cycles found in the analyzed source") })
                        .shouldBe("Benchmark data must produce a complete acyclic result", present)
                }
                if (iteration == 0) warmupMs = elapsedMs else durations += elapsedMs
            }
            val ordered = durations.sorted()
            println(
                "SOURCE_BENCHMARK count=$classCount ide=$version os=${System.getProperty("os.name")} " +
                    "jdk=${System.getProperty("java.version")} cpus=${Runtime.getRuntime().availableProcessors()} " +
                    "startup_and_indexing_ms=$startupAndIndexingMs warmup_ms=$warmupMs " +
                    "warm_ms=${durations.joinToString(",")} " +
                    "median_ms=${ordered[2]} p95_nearest_rank_ms=${ordered[4]}",
            )
            val targetMs = if (classCount == 1_000) 5_000L else 30_000L
            assertTrue(ordered[2] < targetMs, "Warm median exceeded target: ${ordered[2]} ms")
            assertTrue(ordered[4] < targetMs, "Warm p95 exceeded target: ${ordered[4]} ms")
        }
    }

    private fun projectFixture(classCount: Int): Path {
        require(classCount % 2 == 0)
        val project = testDirectory.resolve("mixed-$classCount")
        val fixtureRoot =
            Path.of(requireNotNull(System.getProperty("shamash.fixture.root"))).resolve("java")
        Files.createDirectories(project.resolve(".idea"))
        Files.copy(fixtureRoot.resolve("fixture.iml"), project.resolve("fixture.iml"))
        Files.copy(fixtureRoot.resolve(".idea/modules.xml"), project.resolve(".idea/modules.xml"))
        Files.copy(fixtureRoot.resolve(".idea/misc.xml"), project.resolve(".idea/misc.xml"))
        val java = Files.createDirectories(project.resolve("src/main/java/perf"))
        val kotlin = Files.createDirectories(project.resolve("src/main/kotlin/perf"))
        for (index in 0 until classCount / 2) {
            val id = index.toString().padStart(5, '0')
            Files.writeString(
                java.resolve("Java$id.java"),
                "package perf; public final class Java$id { public Kotlin$id dependency; }",
            )
            Files.writeString(
                kotlin.resolve("Kotlin$id.kt"),
                "package perf\nclass Kotlin$id(val dependency: Java$id)",
            )
        }
        return project
    }
}

@Remote("io.shamash.intellij.plugin.analysis.IdeAnalysisCoordinator")
interface RemoteAnalysisCoordinator {
    fun state(): RemoteAnalysisState
}

@Remote("io.shamash.intellij.plugin.analysis.IdeAnalysisState")
interface RemoteAnalysisState {
    fun getRequestGeneration(): Long

    fun getActive(): Boolean

    fun getCurrentCompleteFileCount(): Int?
}
