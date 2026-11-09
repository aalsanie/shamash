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

import com.intellij.driver.sdk.*
import com.intellij.driver.sdk.ui.components.*
import com.intellij.ide.starter.ci.CIServer
import com.intellij.ide.starter.ci.NoCIServer
import com.intellij.ide.starter.di.di
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.ide.starter.ide.IdeProductProvider
import com.intellij.ide.starter.models.TestCase
import com.intellij.ide.starter.plugins.PluginConfigurator
import com.intellij.ide.starter.project.LocalProjectInfo
import com.intellij.ide.starter.runner.Starter
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes

/**
 * Real out-of-process IDE journeys. These are deliberately separate from CodeInsightFixtureTestCase.
 * The Driver interacts with the actual registered plugin window and the Starter checks IDE errors.
 */
class ShamashStarterJourneysTest {
    init {
        di =
            DI {
                extend(di)
                bindSingleton<CIServer>(overrides = true) {
                    object : CIServer by NoCIServer {
                        override fun reportTestFailure(
                            testName: String,
                            message: String,
                            details: String,
                            linkToLogs: String?,
                        ) {
                            throw AssertionError("$testName: $message\n$details\n${linkToLogs.orEmpty()}")
                        }
                    }
                }
            }
    }

    @Test
    fun firstRunWithoutSourcesIsHonestAndDoesNotRewriteExistingProjectData() {
        val project = fixture("empty")
        val config = project.resolve("shamash/configs/psi.yml")
        val baseline = project.resolve(".shamash/baseline/asm-baseline.json")
        val report = project.resolve(".shamash/reports/previous.json")
        val originals = listOf(config, baseline, report).associateWith(Files::readAllBytes)
        var openedProject: Path? = null
        context("no-source", project)
            .runIdeWithDriver()
            .useDriverAndCloseIde {
                waitForIndicators(5.minutes)
                openedProject = Path.of(singleProject().getBasePath())
                ideFrame {
                    invokeAction("io.shamash.open")
                    x(xQuery { byAccessibleName("Analyze project source") })
                        .shouldBe("Shamash must show the explicit analysis action", present)
                    x(xQuery { byAccessibleName("Analyze project source") }).click()
                    x(xQuery { byVisibleText("No supported production source found") })
                        .shouldBe("No-source result must be explicit, not an empty success", present)
                }
            }
        val actualProject = requireNotNull(openedProject) { "Driver did not expose the opened project" }
        originals.forEach { (file, bytes) ->
            assertArrayEquals(bytes, Files.readAllBytes(actualProject.resolve(project.relativize(file))), "Shamash modified $file")
        }
    }

    @Test
    fun javaCycleCanBeInvestigatedFromFindingsWithoutGeneratingReports() {
        val project = fixture("java")
        var openedProject: Path? = null
        context("java-cycle", project)
            .runIdeWithDriver()
            .useDriverAndCloseIde {
                waitForIndicators(5.minutes)
                openedProject = Path.of(singleProject().getBasePath())
                ideFrame {
                    invokeAction("io.shamash.open")
                    x(xQuery { byAccessibleName("Analyze project source") }).click()
                    x(xQuery { byVisibleText("Cyclic group of 2 packages") })
                        .shouldBe("Both directions of the Java package cycle must be observed", present)
                    x(xQuery { byAccessibleName("Directed edges of the representative cycle") })
                        .shouldBe("Cycle investigation must retain its directed source evidence", present)
                }
            }
        assertFalse(Files.exists(requireNotNull(openedProject).resolve(".shamash")), "IDE analysis must not export reports")
    }

    @Test
    fun mixedJavaKotlinCycleIsAvailableThroughTheLegacyRunActionAndExplore() {
        val project = fixture("mixed")
        val config = project.resolve("shamash/configs/asm.yml")
        val report = project.resolve(".shamash/reports/previous.json")
        val originals = listOf(config, report).associateWith(Files::readAllBytes)
        var openedProject: Path? = null
        context("mixed-cycle", project)
            .runIdeWithDriver()
            .useDriverAndCloseIde {
                waitForIndicators(5.minutes)
                openedProject = Path.of(singleProject().getBasePath())
                ideFrame {
                    invokeAction("io.shamash.asm.runScan")
                    x(xQuery { byVisibleText("Cyclic group of 2 packages") })
                        .shouldBe("Old Build Scan must invoke source analysis, including Kotlin", present)
                    x(xQuery { byVisibleText("Explore") }).click()
                    x(xQuery { byAccessibleName("IDE module, package and declaration tree") })
                        .shouldBe("Explore must expose production-source declarations", present)
                }
            }
        val actualProject = requireNotNull(openedProject) { "Driver did not expose the opened project" }
        originals.forEach { (file, bytes) ->
            assertArrayEquals(bytes, Files.readAllBytes(actualProject.resolve(project.relativize(file))), "Shamash modified $file")
        }
    }

    @Test
    fun unresolvedProductionSourceNeverClaimsThereAreNoCycles() {
        val project = fixture("partial")
        context("partial-resolution", project)
            .runIdeWithDriver()
            .useDriverAndCloseIde {
                waitForIndicators(5.minutes)
                ideFrame {
                    invokeAction("io.shamash.open")
                    x(xQuery { byAccessibleName("Analyze project source") }).click()
                    x(xQuery { byVisibleText("Some source dependencies could not be analyzed") })
                        .shouldBe("Unresolved production references must produce a partial result", present)
                }
            }
    }


    @Test
    fun kotlinOnlyCycleAppearsInFindingsAndExplore() {
        val project = fixture("kotlin")
        context("kotlin-cycle", project)
            .runIdeWithDriver()
            .useDriverAndCloseIde {
                waitForIndicators(5.minutes)
                ideFrame {
                    invokeAction("io.shamash.open")
                    x(xQuery { byAccessibleName("Analyze project source") }).click()
                    x(xQuery { byVisibleText("Cyclic group of 2 packages") })
                        .shouldBe("Kotlin-only references must produce a package cycle", present)
                    x(xQuery { byVisibleText("Explore") }).click()
                    x(xQuery { byAccessibleName("IDE module, package and declaration tree") })
                        .shouldBe("Kotlin-only declarations must appear in Explore", present)
                }
            }
    }

    @Test
    fun acyclicProductionSourceHasNoCycleFindingButExploreRemainsAvailable() {
        val project = fixture("acyclic")
        context("acyclic", project)
            .runIdeWithDriver()
            .useDriverAndCloseIde {
                waitForIndicators(5.minutes)
                ideFrame {
                    invokeAction("io.shamash.open")
                    x(xQuery { byAccessibleName("Analyze project source") }).click()
                    x(xQuery { byVisibleText("No package cycles found in the analyzed source") })
                        .shouldBe("Complete acyclic source must not be reported as partial or cyclic", present)
                    x(xQuery { byVisibleText("Explore") }).click()
                    x(xQuery { byAccessibleName("IDE module, package and declaration tree") })
                        .shouldBe("Acyclic dependencies must remain explorable", present)
                }
            }
    }

    private fun fixture(name: String): Path =
        Path.of(requireNotNull(System.getProperty("shamash.fixture.root")) { "Missing fixture root" })
            .resolve(name)
            .toAbsolutePath()
            .normalize()
            .also { require(Files.isDirectory(it)) { "Missing journey fixture: $it" } }

    private fun context(
        test: String,
        project: Path,
    ) = Starter.newContext(
        testName = "shamash-${test}-${ideVersion().replace('.', '-')}",
        TestCase(if (ideVersion().startsWith("2026.")) IdeProductProvider.IU else IdeProductProvider.IC, LocalProjectInfo(project))
            .withVersion(ideVersion()),
    ).apply {
        val pluginArchive =
            Path.of(requireNotNull(System.getProperty("path.to.build.plugin")) { "No built plugin archive" })
        require(Files.isRegularFile(pluginArchive)) { "Missing Shamash plugin archive: $pluginArchive" }
        PluginConfigurator(this).installPluginFromPath(pluginArchive)
        applyVMOptionsPatch {
            addSystemProperty("idea.trust.all.projects", true)
            addSystemProperty("ide.show.tips.on.startup.default.value", false)
        }
    }

    private fun ideVersion(): String =
        System.getProperty("shamash.ide.version")?.takeIf(String::isNotBlank) ?: "2024.2.1"
}
