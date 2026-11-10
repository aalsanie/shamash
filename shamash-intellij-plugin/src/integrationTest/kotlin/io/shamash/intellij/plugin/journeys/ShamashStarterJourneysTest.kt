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
import com.intellij.driver.sdk.getOpenProjects
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.singleProject
import com.intellij.driver.sdk.ui.components.ideFrame
import com.intellij.driver.sdk.ui.components.list
import com.intellij.driver.sdk.ui.components.tree
import com.intellij.driver.sdk.ui.present
import com.intellij.driver.sdk.ui.shouldBe
import com.intellij.driver.sdk.ui.shouldNot
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
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.w3c.dom.Element
import java.awt.event.KeyEvent
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Real out-of-process IDE journeys. These are deliberately separate from CodeInsightFixtureTestCase.
 * The Driver interacts with the actual registered plugin window and the Starter checks IDE errors.
 */
class ShamashStarterJourneysTest {
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
    fun firstRunWithoutSourcesIsHonestAndDoesNotRewriteExistingProjectData() {
        val project = fixture("empty")
        val config = project.resolve("shamash/configs/psi.yml")
        val baseline = project.resolve(".shamash/baseline/asm-baseline.json")
        val report = project.resolve(".shamash/reports/previous.json")
        val workspace = project.resolve(".idea/workspace.xml")
        val originals = listOf(config, baseline, report).associateWith(Files::readAllBytes)
        assertTrue(Files.readString(workspace).contains("acknowledgedVersion\" value=\"0"))
        var openedProject: Path? = null
        context("no-source", project)
            .runIdeWithDriver()
            .useDriverAndCloseIde {
                waitForIndicators(5.minutes)
                openedProject = Path.of(requireNotNull(singleProject().getPresentableUrl()))
                ideFrame {
                    invokeAction("io.shamash.open")
                    x(xQuery { byVisibleText("Findings") }).shouldBe("Findings must be available", present)
                    x(xQuery { byVisibleText("Explore") }).shouldBe("Explore must be available", present)
                    x(xQuery { byVisibleText("Configuration") }).shouldNot(present)
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
        assertLegacyWorkspace(actualProject.resolve(".idea/workspace.xml"))
    }

    @Tag("essential")
    @Test
    fun javaCycleCanBeInvestigatedFromFindingsWithoutGeneratingReports() {
        val project = fixture("java")
        assertFalse(Files.exists(project.resolve("shamash")))
        assertFalse(Files.exists(project.resolve("build.gradle.kts")))
        var openedProject: Path? = null
        var evidenceLabel = ""
        context("java-cycle", project)
            .runIdeWithDriver()
            .useDriverAndCloseIde {
                waitForIndicators(5.minutes)
                openedProject = Path.of(requireNotNull(singleProject().getPresentableUrl()))
                ideFrame {
                    invokeAction("io.shamash.open")
                    invokeAction("MaximizeToolWindow")
                    x(xQuery { byAccessibleName("Analyze project source") }).click()
                    x(xQuery { byVisibleText("Cyclic group of 2 packages") })
                        .shouldBe("Both directions of the Java package cycle must be observed", present)
                    val findings = list { byAccessibleName("Package cycle findings") }
                    findings.shouldBe("Findings list must be present", present)
                    assertEquals(
                        1,
                        findings.items.size,
                        "The cycle must be represented once: " + findings.items,
                    )
                    val evidence = list { byAccessibleName("Verified source references") }
                    evidence.shouldBe("The selected cycle edge must retain a source witness", present)
                    evidenceLabel = evidence.items.firstOrNull()
                        ?: throw AssertionError("The Java cycle has no retained source reference")
                    assertTrue(
                        evidenceLabel.contains("left.Left#dependency") || evidenceLabel.contains("right.Right#dependency"),
                        "Unexpected Java source witness: " + evidenceLabel,
                    )
                    evidence.clickItem(evidenceLabel)
                    x(xQuery { byVisibleText("Open source") }).click()
                }
                val snapshot =
                    withContext {
                        val manager = service(RemoteFileEditorManager::class, singleProject())
                        val file = requireNotNull(manager.getSelectedFiles().firstOrNull())
                        val editor = requireNotNull(manager.getSelectedTextEditor())
                        EditorSnapshot(file.getPath(), editor.getDocument().getText(), editor.getCaretModel().getOffset())
                    }
                val owner = evidenceLabel.substringBefore(" → ")
                val target = evidenceLabel.substringAfter(" → ")
                val expectedFile =
                    when (owner) {
                        "left.Left#dependency" -> "Left.java"
                        "right.Right#dependency" -> "Right.java"
                        else -> throw AssertionError("Unexpected source owner: " + owner)
                    }
                assertTrue(snapshot.path.endsWith("/" + expectedFile), "Navigation opened the wrong source file: " + snapshot.path)
                val targetStart = snapshot.documentText.indexOf(target)
                assertTrue(targetStart >= 0, "The verified target is absent from the opened document: " + target)
                val classStart = targetStart + target.lastIndexOf('.') + 1
                assertTrue(
                    snapshot.caretOffset == targetStart || snapshot.caretOffset == classStart,
                    "Navigation did not land on the verified reference: " + snapshot.caretOffset,
                )
                assertEquals(target, snapshot.documentText.substring(targetStart, targetStart + target.length))
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
                openedProject = Path.of(requireNotNull(singleProject().getPresentableUrl()))
                ideFrame {
                    invokeAction("io.shamash.asm.runScan")
                    invokeAction("MaximizeToolWindow")
                    x(xQuery { byVisibleText("Cyclic group of 2 packages") })
                        .shouldBe("Old Build Scan must invoke source analysis, including Kotlin", present)
                    val findings = list { byAccessibleName("Package cycle findings") }
                    findings.shouldBe("Findings list must be present", present)
                    assertEquals(
                        1,
                        findings.items.size,
                        "The mixed-language cycle must be represented once: " + findings.items,
                    )
                    x(xQuery { byVisibleText("Explore") }).click()
                    val tree = tree(xQuery { byAccessibleName("IDE module, package and declaration tree") })
                    tree.expandAll(30.seconds)
                    val paths = tree.collectExpandedPaths().map { it.path }
                    assertTrue(paths.any { path -> path.any { it.contains("JavaOwner") } }, "The Java declaration must remain explorable")
                    assertTrue(
                        paths.any { path ->
                            path.any { it.contains("KotlinPeer") }
                        },
                        "The Kotlin declaration must remain explorable",
                    )
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
                    invokeAction("MaximizeToolWindow")
                    x(xQuery { byAccessibleName("Analyze project source") }).click()
                    x(xQuery { byVisibleText("Some source dependencies could not be analyzed") })
                        .shouldBe("Unresolved production references must produce a partial result", present)
                    x(xQuery { byVisibleText("No cycles observed in the available evidence; analysis is incomplete.") })
                        .shouldBe("Incomplete evidence must never be presented as cycle-free", present)
                }
            }
    }

    @Test
    fun kotlinOnlyCycleAppearsInFindingsAndExplore() {
        val project = fixture("kotlin")
        assertFalse(Files.exists(project.resolve("src/main/java")))
        assertFalse(Files.exists(project.resolve("shamash")))
        assertFalse(Files.exists(project.resolve("build.gradle.kts")))
        context("kotlin-cycle", project)
            .runIdeWithDriver()
            .useDriverAndCloseIde {
                waitForIndicators(5.minutes)
                ideFrame {
                    invokeAction("io.shamash.open")
                    invokeAction("MaximizeToolWindow")
                    x(xQuery { byAccessibleName("Analyze project source") }).click()
                    x(xQuery { byVisibleText("Cyclic group of 2 packages") })
                        .shouldBe("Kotlin-only references must produce a package cycle", present)
                    val findings = list { byAccessibleName("Package cycle findings") }
                    findings.shouldBe("Findings list must be present", present)
                    assertEquals(
                        1,
                        findings.items.size,
                        "The Kotlin cycle must be represented once: " + findings.items,
                    )
                    x(xQuery { byVisibleText("Explore") }).click()
                    val tree = tree(xQuery { byAccessibleName("IDE module, package and declaration tree") })
                    tree.expandAll(30.seconds)
                    val paths = tree.collectExpandedPaths().map { it.path }
                    for (declaration in listOf(
                        "KotlinOwner",
                        "RightBatch",
                        "Container",
                        "Payload",
                        "Registry",
                        "defaultPayload",
                        "create",
                    )) {
                        assertTrue(
                            paths.any { path -> path.any { it.contains(declaration) } },
                            "Missing Kotlin declaration " + declaration + ": " + paths,
                        )
                    }
                    tree.clickRow(
                        tree.collectExpandedPaths().firstOrNull { path -> path.path.any { it.contains("RightBatch") } }?.row
                            ?: throw AssertionError("Missing type alias in Explore tree"),
                    )
                    val relationships = list { byAccessibleName("Source relationships") }
                    assertTrue(
                        relationships.items.any { it.contains("Container") },
                        "The type alias must resolve its generic container target",
                    )
                    assertTrue(
                        relationships.items.any { it.contains("Payload") },
                        "The type alias must resolve its nested generic argument",
                    )
                }
            }
    }

    @Test
    fun acyclicProductionSourceCanBeExploredAndReanalyzedFromUnsavedEdits() {
        val project = fixture("acyclic")
        val beta = project.resolve("src/main/java/beta/Beta.java")
        val originalBeta = Files.readString(beta)
        val editedBeta = "package beta;\npublic final class Beta { alpha.Alpha owner; }\n"
        var openedProject: Path? = null
        context("acyclic", project)
            .runIdeWithDriver()
            .useDriverAndCloseIde {
                waitForIndicators(5.minutes)
                openedProject = Path.of(requireNotNull(singleProject().getPresentableUrl()))
                ideFrame {
                    invokeAction("io.shamash.open")
                    invokeAction("MaximizeToolWindow")
                    x(xQuery { byAccessibleName("Analyze project source") }).click()
                    x(xQuery { byVisibleText("No package cycles found in the analyzed source") })
                        .shouldBe("Complete acyclic source must not be reported as partial or cyclic", present)
                    x(xQuery { byVisibleText("Explore") }).click()
                    x(xQuery { byAccessibleName("IDE module, package and declaration tree") })
                        .shouldBe("Acyclic dependencies must remain explorable", present)
                    invokeAction("GotoFile")
                    keyboard {
                        enterText("Beta.java")
                        enter()
                    }
                }
                val selectedPath =
                    withContext {
                        val manager = service(RemoteFileEditorManager::class, singleProject())
                        requireNotNull(manager.getSelectedFiles().firstOrNull()).getPath()
                    }
                assertTrue(selectedPath.endsWith("/Beta.java"), "Goto File opened the wrong editor: " + selectedPath)
                ideFrame {
                    keyboard {
                        hotKey(KeyEvent.VK_CONTROL, KeyEvent.VK_A)
                        enterText(editedBeta)
                    }
                    x(xQuery { byVisibleText("Code changed since this analysis") })
                        .shouldBe("An unsaved source edit must invalidate the previous result", present)
                }
                val actualBeta = requireNotNull(openedProject).resolve("src/main/java/beta/Beta.java")
                assertEquals(originalBeta, Files.readString(actualBeta), "The edit must remain unsaved until explicitly saved")
                ideFrame {
                    x(xQuery { byAccessibleName("Analyze project source") }).click()
                    x(xQuery { byVisibleText("Cyclic group of 2 packages") })
                        .shouldBe("Analysis must include the unsaved reverse dependency", present)
                    val findings = list { byAccessibleName("Package cycle findings") }
                    findings.shouldBe("Findings list must be present", present)
                    assertEquals(
                        1,
                        findings.items.size,
                        "The new cycle must be represented once: " + findings.items,
                    )
                    invokeAction("SaveAll")
                }
                assertTrue(Files.readString(actualBeta).contains("alpha.Alpha owner"), "SaveAll did not persist the edited source")
            }
        assertFalse(Files.exists(requireNotNull(openedProject).resolve(".shamash")))
    }

    @Test
    fun duplicatePackagesRemainDistinctAndProjectDisposalCancelsActiveAnalysis() {
        val project = fixture("multi-module")
        var openedProject: Path? = null
        context("multi-module", project)
            .runIdeWithDriver()
            .useDriverAndCloseIde {
                waitForIndicators(5.minutes)
                openedProject = Path.of(requireNotNull(singleProject().getPresentableUrl()))
                ideFrame {
                    invokeAction("io.shamash.open")
                    invokeAction("MaximizeToolWindow")
                    x(xQuery { byAccessibleName("Analyze project source") }).click()
                    x(xQuery { byVisibleText("No package cycles found in the analyzed source") })
                        .shouldBe("A one-way module dependency must remain acyclic", present)
                    x(xQuery { byVisibleText("Explore") }).click()
                    val tree = tree(xQuery { byAccessibleName("IDE module, package and declaration tree") })
                    tree.expandAll(30.seconds)
                    val sharedPackages = tree.collectExpandedPaths().filter { it.path.lastOrNull() == "shared" }
                    assertEquals(2, sharedPackages.size, "The same package name must remain distinct across IDE modules")
                    tree.clickRow(
                        tree.collectExpandedPaths().firstOrNull { it.path.lastOrNull() == "multi-a" }?.row
                            ?: throw AssertionError("Missing multi-a IDE module in Explore tree"),
                    )
                    val relationships = list { byAccessibleName("Source relationships") }
                    assertTrue(
                        relationships.items.any { it.contains("multi-b (IDE module)") },
                        "The module dependency must retain its target module identity: " + relationships.items,
                    )
                }
                val actualProject = requireNotNull(openedProject)
                Files.writeString(actualProject.resolve("multi-a/src/main/java/slow/Load.java"), slowSource())
                ideFrame { invokeAction("Synchronize") }
                waitForIndicators(5.minutes)
                ideFrame {
                    x(xQuery { byVisibleText("Code changed since this analysis") })
                        .shouldBe("A refreshed source file must invalidate the previous result", present)
                    x(xQuery { byAccessibleName("Analyze project source") }).click()
                    x(xQuery { byVisibleText("Analyzing source dependencies…") })
                        .shouldBe("The analysis must expose its active state before cancellation", present)
                    x(xQuery { byAccessibleName("Cancel source analysis") }).click()
                    x(xQuery { byVisibleText("Analysis cancelled") })
                        .shouldBe("The real IDE cancellation action must cancel active source analysis", present)
                }
                Thread.sleep(1_000)
                ideFrame {
                    x(xQuery { byVisibleText("Analysis cancelled") })
                        .shouldBe("A late result must not replace the cancelled state", present)
                    x(xQuery { byAccessibleName("Analyze project source") }).click()
                    x(xQuery { byVisibleText("Analyzing source dependencies…") })
                        .shouldBe("A subsequent request must enter the active state", present)
                    invokeAction("CloseProject")
                }
                waitFor(30.seconds, errorMessage = "the project is disposed") {
                    getOpenProjects().isEmpty()
                }
                Thread.sleep(1_000)
            }
        assertFalse(Files.exists(requireNotNull(openedProject).resolve(".shamash")), "IDE analysis must not export reports")
    }

    private fun assertLegacyWorkspace(workspace: Path) {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(workspace.toFile())
        val components = document.getElementsByTagName("component")
        val migration =
            (0 until components.length)
                .map { components.item(it) as Element }
                .firstOrNull { it.getAttribute("name") == "ShamashSourceMigration" }
        val options = requireNotNull(migration) { "The source migration state was not persisted" }.getElementsByTagName("option")
        val acknowledged =
            (0 until options.length)
                .map { options.item(it) as Element }
                .firstOrNull { it.getAttribute("name") == "acknowledgedVersion" }
        assertEquals("1", requireNotNull(acknowledged).getAttribute("value"))
        val windows = document.getElementsByTagName("window_info")
        val shamash =
            (0 until windows.length)
                .map { windows.item(it) as Element }
                .firstOrNull { it.getAttribute("id") == "Shamash" }
        assertEquals("right", requireNotNull(shamash) { "The saved Shamash tool-window position was lost" }.getAttribute("anchor"))
    }

    private fun slowSource(): String =
        buildString(2_500_000) {
            appendLine("package slow;")
            appendLine("final class Load {")
            appendLine("    void run() {")
            repeat(120_000) { appendLine("        Helper.run();") }
            appendLine("    }")
            appendLine("}")
            appendLine("final class Helper { static void run() {} }")
        }

    private fun fixture(name: String): Path {
        val source =
            Path
                .of(requireNotNull(System.getProperty("shamash.fixture.root")) { "Missing fixture root" })
                .resolve(name)
                .toAbsolutePath()
                .normalize()
        require(Files.isDirectory(source)) { "Missing journey fixture: $source" }
        val destination = testDirectory.resolve(name)
        Files.walk(source).use { entries ->
            entries.forEach { entry ->
                val target = destination.resolve(source.relativize(entry))
                if (Files.isDirectory(entry)) {
                    Files.createDirectories(target)
                } else {
                    Files.copy(entry, target)
                }
            }
        }
        return destination
    }

    private fun context(
        test: String,
        project: Path,
    ) = Starter
        .newContext(
            testName = "shamash-$test-${ideVersion().replace('.', '-')}",
            TestCase(
                if (ideVersion() == "2024.2.1" ||
                    ideVersion() == "2025.2"
                ) {
                    IdeProductProvider.IC
                } else {
                    IdeProductProvider.IU
                },
                LocalProjectInfo(project),
            ).withVersion(ideVersion()),
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

    private fun ideVersion(): String = System.getProperty("shamash.ide.version")?.takeIf(String::isNotBlank) ?: "2024.2.1"
}

private data class EditorSnapshot(
    val path: String,
    val documentText: String,
    val caretOffset: Int,
)

@Remote("com.intellij.openapi.fileEditor.FileEditorManager")
interface RemoteFileEditorManager {
    fun getSelectedFiles(): Array<RemoteVirtualFile>

    fun getSelectedTextEditor(): RemoteEditor?
}

@Remote("com.intellij.openapi.vfs.VirtualFile")
interface RemoteVirtualFile {
    fun getPath(): String
}

@Remote("com.intellij.openapi.editor.Editor")
interface RemoteEditor {
    fun getDocument(): RemoteDocument

    fun getCaretModel(): RemoteCaretModel
}

@Remote("com.intellij.openapi.editor.Document")
interface RemoteDocument {
    fun getText(): String
}

@Remote("com.intellij.openapi.editor.CaretModel")
interface RemoteCaretModel {
    fun getOffset(): Int
}
