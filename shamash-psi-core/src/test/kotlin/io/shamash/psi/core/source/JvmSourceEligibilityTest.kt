/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.psi.core.source

import com.intellij.facet.FacetManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.module.JavaModuleType
import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.ContentIterator
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.builders.EmptyModuleFixtureBuilder
import com.intellij.testFramework.fixtures.CodeInsightFixtureTestCase
import org.jetbrains.jps.model.java.JavaSourceRootProperties
import org.jetbrains.jps.model.java.JavaSourceRootType
import org.jetbrains.kotlin.config.SourceKotlinRootType
import org.jetbrains.kotlin.idea.facet.KotlinFacet
import org.jetbrains.kotlin.idea.facet.KotlinFacetType
import org.jetbrains.kotlin.platform.CommonPlatforms
import org.jetbrains.kotlin.platform.TargetPlatform
import org.jetbrains.kotlin.platform.js.JsPlatforms
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.platform.konan.NativePlatforms
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JvmSourceEligibilityTest : CodeInsightFixtureTestCase<EmptyModuleFixtureBuilder<*>>() {
    override fun setUp() {
        super.setUp()
        PsiTestUtil.addSourceRoot(myModule, myFixture.tempDirFixture.findOrCreateDir("src/main/java"))
    }

    fun testEligibilityRecordRequiresCompleteIndexedOwnership() {
        val root = myFixture.tempDirFixture.findOrCreateDir("src/main/java")
        assertFalse(SourceEligibility().included)
        assertFalse(SourceEligibility(module = myModule).included)
        assertFalse(SourceEligibility(module = myModule, root = root, exclusion = SourceExclusion.TEST).included)
        assertTrue(SourceEligibility(module = myModule, root = root).included)
    }

    fun testOrdinaryJavaAndMixedKotlinOnJavaRootRequireNoFacet() {
        myFixture.addFileToProject("src/main/java/demo/Service.java", "package demo; class Service {}")
        myFixture.addFileToProject("src/main/java/demo/KotlinService.kt", "package demo\nclass KotlinService")
        val selected = collect()
        assertEquals(2, selected.files.size)
        assertEquals(0, selected.uncertainFiles)
        assertTrue(selected.excludedReasons.isEmpty())
    }

    fun testKotlinFacetJVMIncludesRegisteredKotlinRoots() {
        val root = myFixture.tempDirFixture.findOrCreateDir("src/main/kotlin")
        PsiTestUtil.addSourceRoot(myModule, root, SourceKotlinRootType, JavaSourceRootProperties("", false))
        myFixture.addFileToProject("src/main/kotlin/demo/App.kt", "package demo\nclass App")
        assertEquals(1, collect().uncertainFiles)
        facet(myModule, JvmPlatforms.defaultJvmPlatform)
        val result = collect()
        assertEquals(1, result.files.size)
        assertEquals(0, result.uncertainFiles)
    }

    fun testFacetExcludesJsNativeAndCommonWithoutGuessingFromPaths() {
        val root = myFixture.tempDirFixture.findOrCreateDir("src/main/kotlin")
        PsiTestUtil.addSourceRoot(myModule, root, SourceKotlinRootType, JavaSourceRootProperties("", false))
        myFixture.addFileToProject("src/main/kotlin/demo/Platform.kt", "package demo\nclass Platform")
        myFixture.addFileToProject("src/main/java/demo/JavaFile.java", "package demo; class JavaFile {}")
        for (platform in listOf(
            JsPlatforms.defaultJsPlatform,
            NativePlatforms.unspecifiedNativePlatform,
            CommonPlatforms.defaultCommonPlatform,
        )) {
            facet(myModule, platform)
            val result = collect()
            assertEquals(0, result.files.size, platform.toString())
            assertEquals(2, result.excludedReasons[SourceExclusion.NON_JVM.name], platform.toString())
            assertEquals(0, result.uncertainFiles)
        }
        facet(myModule, JvmPlatforms.defaultJvmPlatform)
        assertEquals(2, collect().files.size)
    }

    fun testMissingFacetTargetIsUncertainNotJVM() {
        myFixture.addFileToProject("src/main/java/demo/Maybe.kt", "package demo\nclass Maybe")
        facet(myModule, null)
        val result = collect()
        assertEquals(0, result.files.size)
        assertEquals(1, result.uncertainFiles)
        assertEquals(1, result.excludedReasons[SourceExclusion.UNKNOWN_PLATFORM.name])
    }

    fun testGeneratedKotlinAndJavaRootMetadataIsExcluded() {
        val generatedKotlin = myFixture.tempDirFixture.findOrCreateDir("generated/kotlin")
        val generatedJava = myFixture.tempDirFixture.findOrCreateDir("generated/java")
        PsiTestUtil.addSourceRoot(myModule, generatedKotlin, SourceKotlinRootType, JavaSourceRootProperties("", true))
        PsiTestUtil.addSourceRoot(myModule, generatedJava, JavaSourceRootType.SOURCE, JavaSourceRootProperties("", true))
        myFixture.addFileToProject("generated/kotlin/demo/Generated.kt", "package demo\nclass Generated")
        myFixture.addFileToProject("generated/java/demo/GeneratedJava.java", "package demo; class GeneratedJava {}")
        val result = collect()
        assertEquals(0, result.files.size)
        assertEquals(2, result.excludedReasons[SourceExclusion.GENERATED.name])
    }

    fun testProductionTestAndExcludedRootsAreSeparated() {
        val testRoot = myFixture.tempDirFixture.findOrCreateDir("src/test/kotlin")
        PsiTestUtil.addSourceRoot(myModule, testRoot, true)
        val excludedRoot = myFixture.tempDirFixture.findOrCreateDir("excluded")
        PsiTestUtil.addExcludedRoot(myModule, excludedRoot)
        myFixture.addFileToProject("src/test/kotlin/Test.kt", "class Test")
        myFixture.addFileToProject("excluded/Other.kt", "class Other")
        val result = collect()
        assertTrue(result.files.isEmpty(), "Unexpected production files: ${result.files.map { it.file.url }}")
    }

    fun testActualSeparateSourceSetModulesOnlyIncludeJvmModule() {
        val jsRoot = myFixture.tempDirFixture.findOrCreateDir("platform-js")
        val commonRoot = myFixture.tempDirFixture.findOrCreateDir("platform-common")
        val jvmRoot = myFixture.tempDirFixture.findOrCreateDir("platform-jvm")
        val jvm = PsiTestUtil.addModule(project, JavaModuleType.getModuleType(), "app-jvm", jvmRoot)
        val js = PsiTestUtil.addModule(project, JavaModuleType.getModuleType(), "app-js", jsRoot)
        val common = PsiTestUtil.addModule(project, JavaModuleType.getModuleType(), "app-common", commonRoot)
        for ((module, root) in listOf(jvm to jvmRoot, js to jsRoot, common to commonRoot)) {
            PsiTestUtil.addSourceRoot(module, root, SourceKotlinRootType, JavaSourceRootProperties("", false))
        }
        facet(jvm, JvmPlatforms.defaultJvmPlatform)
        facet(js, JsPlatforms.defaultJsPlatform)
        facet(common, CommonPlatforms.defaultCommonPlatform)
        val jvmSource = myFixture.addFileToProject("platform-jvm/Jvm.kt", "class Jvm")
        val jsSource = myFixture.addFileToProject("platform-js/Js.kt", "class Js")
        val commonSource = myFixture.addFileToProject("platform-common/Common.kt", "class Common")
        val result = collect()
        assertEquals(1, result.files.size)
        assertEquals(
            jvmSource.virtualFile.url,
            result.files
                .single()
                .file.url,
        )
        val sourceEligibility = JvmSourceEligibility(ProjectFileIndex.getInstance(project))
        ReadAction.compute<Unit, RuntimeException> {
            assertEquals(SourceExclusion.NON_JVM, sourceEligibility.evaluate(jsSource.virtualFile).exclusion)
            assertEquals(SourceExclusion.NON_JVM, sourceEligibility.evaluate(commonSource.virtualFile).exclusion)
            assertTrue(sourceEligibility.evaluate(jvmSource.virtualFile).included)
        }
    }

    fun testDuplicateIndexEntriesDoNotConsumeFileLimit() {
        val root = myFixture.tempDirFixture.findOrCreateDir("src/main/java")
        val first = myFixture.addFileToProject("src/main/java/One.java", "class One {}").virtualFile
        val second = myFixture.addFileToProject("src/main/java/Two.java", "class Two {}").virtualFile
        val ignored = myFixture.addFileToProject("src/main/java/notes.txt", "not source").virtualFile
        val directory = myFixture.tempDirFixture.findOrCreateDir("src/main/java/nested")

        fun duplicateIndex(files: List<com.intellij.openapi.vfs.VirtualFile>): ProjectFileIndex =
            Proxy.newProxyInstance(
                ProjectFileIndex::class.java.classLoader,
                arrayOf(ProjectFileIndex::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "iterateContent" -> {
                        val iterator = args!![0] as ContentIterator
                        files.all { iterator.processFile(it) }
                    }

                    "isInLibraryClasses", "isInLibrarySource", "isInTestSourceContent" -> {
                        false
                    }

                    "isInSourceContent" -> {
                        true
                    }

                    "getModuleForFile" -> {
                        myModule
                    }

                    "getSourceRootForFile" -> {
                        root
                    }

                    else -> {
                        error("Unexpected index method: ${method.name}")
                    }
                }
            } as ProjectFileIndex

        val deduplicated =
            ReadAction.compute<ProjectSourceCollector.Selection, RuntimeException> {
                ProjectSourceCollector(project, duplicateIndex(listOf(directory, ignored, first, first))).collect(1)
            }
        assertEquals(1, deduplicated.files.size)
        assertEquals(
            first.url,
            deduplicated.files
                .single()
                .file.url,
        )
        assertFalse(deduplicated.truncated)

        val limited =
            ReadAction.compute<ProjectSourceCollector.Selection, RuntimeException> {
                ProjectSourceCollector(project, duplicateIndex(listOf(first, first, second))).collect(1)
            }
        assertTrue(limited.truncated)
        assertEquals(listOf(first.url), limited.files.map { it.file.url })
    }

    fun testCollectorCountsRepeatedExclusionsWithoutAdmittingUnsupportedFiles() {
        val root = myFixture.tempDirFixture.findOrCreateDir("src/main/java")
        val first = myFixture.addFileToProject("src/main/java/First.java", "class First {}").virtualFile
        val second = myFixture.addFileToProject("src/main/java/Second.java", "class Second {}").virtualFile
        val third = myFixture.addFileToProject("src/main/java/Third.java", "class Third {}").virtualFile
        val testOne = myFixture.addFileToProject("src/main/java/TestOne.java", "class TestOne {}").virtualFile
        val testTwo = myFixture.addFileToProject("src/main/java/TestTwo.java", "class TestTwo {}").virtualFile
        val notSourceOne = myFixture.addFileToProject("src/main/java/OutsideOne.kt", "class OutsideOne").virtualFile
        val notSourceTwo = myFixture.addFileToProject("src/main/java/OutsideTwo.kt", "class OutsideTwo").virtualFile
        val ignored = myFixture.addFileToProject("src/main/java/notes.txt", "not source").virtualFile
        val entries = listOf(ignored, first, testOne, testTwo, notSourceOne, notSourceTwo, first, second, third)
        val index =
            Proxy.newProxyInstance(
                ProjectFileIndex::class.java.classLoader,
                arrayOf(ProjectFileIndex::class.java),
            ) { _, method, args ->
                val file = args?.firstOrNull() as? com.intellij.openapi.vfs.VirtualFile
                when (method.name) {
                    "iterateContent" -> {
                        val iterator = args!![0] as ContentIterator
                        entries.all { iterator.processFile(it) }
                    }
                    "isInLibraryClasses", "isInLibrarySource" -> false
                    "isInTestSourceContent" -> file == testOne || file == testTwo
                    "isInSourceContent" -> file != notSourceOne && file != notSourceTwo
                    "getModuleForFile" -> myModule
                    "getSourceRootForFile" -> root
                    else -> error("Unexpected index method: ${method.name}")
                }
            } as ProjectFileIndex
        val result =
            ReadAction.compute<ProjectSourceCollector.Selection, RuntimeException> {
                ProjectSourceCollector(project, index).collect(2)
            }
        assertTrue(result.truncated)
        assertEquals(setOf(first.url, second.url), result.files.map { it.file.url }.toSet())
        assertEquals(4, result.excluded)
        assertEquals(2, result.excludedReasons[SourceExclusion.TEST.name])
        assertEquals(2, result.excludedReasons[SourceExclusion.NOT_SOURCE.name])
        assertEquals(0, result.uncertainFiles)
    }

    fun testFacetRevisionChangesOnConfigurationAndRemoval() {
        myFixture.addFileToProject("src/main/java/demo/App.kt", "package demo\nclass App")
        val revisions = JvmSourceScopeRevision.getInstance(project)
        val initial = revisions.revision
        facet(myModule, JsPlatforms.defaultJsPlatform)
        assertTrue(revisions.revision > initial)
        assertEquals(0, collect().files.size)
        facet(myModule, JvmPlatforms.defaultJvmPlatform)
        assertEquals(1, collect().files.size)
        assertTrue(revisions.revision > initial)
    }

    fun testIndexedNonJvmBoundariesCannotBePromotedToProjectSource() {
        val source = myFixture.addFileToProject("src/main/java/demo/Target.java", "package demo; class Target {}")
        val file = source.virtualFile
        val sourceRoot = myFixture.tempDirFixture.findOrCreateDir("src/main/java")
        val unrelatedRoot = myFixture.tempDirFixture.findOrCreateDir("untracked")
        val notSource = myFixture.addFileToProject("untracked/README.md", "not a source file")
        val fake =
            {
                classes: Boolean,
                librarySources: Boolean,
                test: Boolean,
                inSource: Boolean,
                owner: Module?,
                root: com.intellij.openapi.vfs.VirtualFile?,
                ->
                Proxy.newProxyInstance(
                    ProjectFileIndex::class.java.classLoader,
                    arrayOf(ProjectFileIndex::class.java),
                ) { _, method, _ ->
                    when (method.name) {
                        "isInLibraryClasses" -> classes
                        "isInLibrarySource" -> librarySources
                        "isInTestSourceContent" -> test
                        "isInSourceContent" -> inSource
                        "getModuleForFile" -> owner
                        "getSourceRootForFile" -> root
                        else -> error("Unexpected source index call: " + method.name)
                    }
                } as ProjectFileIndex
            }

        fun decision(
            classes: Boolean = false,
            librarySources: Boolean = false,
            test: Boolean = false,
            inSource: Boolean = true,
            owner: Module? = myModule,
            root: com.intellij.openapi.vfs.VirtualFile? = sourceRoot,
        ) = JvmSourceEligibility(fake(classes, librarySources, test, inSource, owner, root)).evaluate(file)

        assertEquals(SourceExclusion.LIBRARY, decision(classes = true).exclusion)
        assertEquals(SourceExclusion.LIBRARY, decision(librarySources = true).exclusion)
        assertEquals(SourceExclusion.TEST, decision(test = true).exclusion)
        assertEquals(SourceExclusion.NOT_SOURCE, decision(inSource = false).exclusion)
        assertEquals(SourceExclusion.NOT_SOURCE, decision(owner = null).exclusion)
        assertEquals(SourceExclusion.NOT_SOURCE, decision(root = null).exclusion)
        assertEquals(SourceExclusion.UNSUPPORTED_ROOT, decision(root = unrelatedRoot).exclusion)
        assertTrue(decision().included)
        assertEquals(
            SourceExclusion.NOT_SOURCE,
            JvmSourceEligibility(fake(false, false, false, true, myModule, sourceRoot)).evaluate(notSource.virtualFile).exclusion,
        )
        assertEquals(
            SourceExclusion.NOT_SOURCE,
            JvmSourceEligibility(fake(false, false, false, true, myModule, sourceRoot)).evaluate(sourceRoot).exclusion,
        )
    }

    fun testRemovingPlatformFacetInvalidatesScopeRevision() {
        myFixture.addFileToProject("src/main/java/demo/Plain.kt", "package demo\nclass Plain")
        val scope = JvmSourceScopeRevision.getInstance(project)
        facet(myModule, JsPlatforms.defaultJsPlatform)
        val beforeRemoval = scope.revision
        assertEquals(0, collect().files.size)
        val manager = FacetManager.getInstance(myModule)
        val current = requireNotNull(KotlinFacet.get(myModule))
        WriteCommandAction.runWriteCommandAction(project) {
            manager.createModifiableModel().apply {
                removeFacet(current)
                commit()
            }
        }
        assertTrue(scope.revision > beforeRemoval)
        assertEquals(1, collect().files.size)
    }

    private fun collect(): ProjectSourceCollector.Selection {
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        return ReadAction.compute<ProjectSourceCollector.Selection, RuntimeException> {
            ProjectSourceCollector(project).collect(50_000)
        }
    }

    private fun facet(
        module: Module,
        target: TargetPlatform?,
    ) {
        val existing = KotlinFacet.get(module)
        if (existing != null) {
            WriteCommandAction.runWriteCommandAction(project) {
                existing.configuration.settings.targetPlatform = target
                project.messageBus.syncPublisher(FacetManager.FACETS_TOPIC).facetConfigurationChanged(existing)
            }
            return
        }
        val manager = FacetManager.getInstance(module)
        val configuration = KotlinFacetType.INSTANCE.createDefaultConfiguration()
        configuration.settings.targetPlatform = target
        val facet = manager.createFacet(KotlinFacetType.INSTANCE, "Kotlin", configuration, null)
        WriteCommandAction.runWriteCommandAction(project) {
            manager.createModifiableModel().apply {
                addFacet(facet)
                commit()
            }
        }
    }
}
