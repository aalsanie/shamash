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
package io.shamash.psi.core.engine

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.builders.EmptyModuleFixtureBuilder
import com.intellij.testFramework.fixtures.CodeInsightFixtureTestCase
import io.shamash.psi.core.config.schema.v1.model.Matcher
import io.shamash.psi.core.config.schema.v1.model.ProjectConfigV1
import io.shamash.psi.core.config.schema.v1.model.Role
import io.shamash.psi.core.config.schema.v1.model.ShamashPsiConfigV1
import io.shamash.psi.core.config.schema.v1.model.SourceGlobsV1
import io.shamash.psi.core.config.schema.v1.model.UnknownRulePolicyV1
import io.shamash.psi.core.config.schema.v1.model.ValidationConfigV1
import io.shamash.psi.core.engine.index.ProjectRoleIndex
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ProjectRoleIndexTest : CodeInsightFixtureTestCase<EmptyModuleFixtureBuilder<*>>() {
    fun testMixedJavaAndKotlinClassesAreIndexedTogether() {
        addMixedSources()

        val snapshot = ProjectRoleIndex.getInstance(project).getOrBuild(config())

        assertTrue("example.JavaComponent" in snapshot.classToFilePath, snapshot.classToFilePath.toString())
        assertTrue("example.KotlinComponent" in snapshot.classToFilePath, snapshot.classToFilePath.toString())
        assertTrue("example.KotlinComponent.Nested" in snapshot.classToFilePath, snapshot.classToFilePath.toString())
    }

    fun testKotlinSourceExclusionAppliesToSupplementalCollection() {
        addMixedSources()

        val snapshot =
            ProjectRoleIndex.getInstance(project).getOrBuild(
                config().copy(
                    project =
                        config().project.copy(
                            sourceGlobs = SourceGlobsV1(include = emptyList(), exclude = listOf("**/KotlinComponent.kt")),
                        ),
                ),
            )

        assertTrue("example.JavaComponent" in snapshot.classToFilePath)
        assertFalse("example.KotlinComponent" in snapshot.classToFilePath)
        assertFalse("example.KotlinComponent.Nested" in snapshot.classToFilePath)
    }

    fun testUnchangedConfigurationReusesSnapshot() {
        addMixedSources()
        val index = ProjectRoleIndex.getInstance(project)
        val requested = config()

        val first = index.getOrBuild(requested)
        val second = index.getOrBuild(requested)

        assertSame(first, second)
    }

    fun testSwitchingRoleConfigurationsAlwaysSelectsMatchingClassification() {
        addMixedSources()
        val index = ProjectRoleIndex.getInstance(project)
        val javaRoles = config().copy(roles = mapOf("java" to role("JavaComponent")))
        val kotlinRoles = config().copy(roles = mapOf("kotlin" to role("KotlinComponent")))

        val first = index.getOrBuild(javaRoles)
        assertEquals("java", first.classToRole["example.JavaComponent"])
        assertFalse("example.KotlinComponent" in first.classToRole)

        val second = index.getOrBuild(kotlinRoles)
        assertEquals("kotlin", second.classToRole["example.KotlinComponent"])
        assertFalse("example.JavaComponent" in second.classToRole)

        val restored = index.getOrBuild(javaRoles)
        assertEquals("java", restored.classToRole["example.JavaComponent"])
        assertFalse("example.KotlinComponent" in restored.classToRole)
    }

    fun testConfigChangeThenNewJavaSourceInvalidatesSelectedCache() {
        addMixedSources()
        val index = ProjectRoleIndex.getInstance(project)
        index.getOrBuild(config())

        val javaOnly =
            config().copy(
                project =
                    config().project.copy(
                        sourceGlobs = SourceGlobsV1(include = emptyList(), exclude = listOf("**/*.kt")),
                    ),
                roles = mapOf("new" to role("NewJavaComponent")),
            )
        val selected = index.getOrBuild(javaOnly)
        assertFalse("example.KotlinComponent" in selected.classToFilePath)
        assertFalse("example.NewJavaComponent" in selected.classToFilePath)

        myFixture.addFileToProject(
            "src/main/java/example/NewJavaComponent.java",
            "package example; public class NewJavaComponent {}",
        )

        val updated = index.getOrBuild(javaOnly)
        assertEquals("new", updated.classToRole["example.NewJavaComponent"])
        assertFalse("example.KotlinComponent" in updated.classToFilePath)
        assertNotSame(selected, updated)
    }

    fun testConfigChangeThenNewKotlinSourceInvalidatesSelectedCache() {
        addMixedSources()
        val index = ProjectRoleIndex.getInstance(project)
        index.getOrBuild(config())
        val kotlinOnly =
            config().copy(
                project =
                    config().project.copy(
                        sourceGlobs = SourceGlobsV1(include = emptyList(), exclude = listOf("**/*.java")),
                    ),
                roles = mapOf("fresh" to role("FreshKotlin")),
            )
        val selected = index.getOrBuild(kotlinOnly)
        assertFalse("example.FreshKotlin" in selected.classToFilePath)

        myFixture.addFileToProject(
            "src/main/kotlin/example/FreshKotlin.kt",
            "package example\nclass FreshKotlin",
        )

        val updated = index.getOrBuild(kotlinOnly)
        assertEquals("fresh", updated.classToRole["example.FreshKotlin"])
        assertFalse("example.JavaComponent" in updated.classToFilePath)
        assertNotSame(selected, updated)
    }

    fun testConfigChangeThenSourceEditInvalidatesSelectedCache() {
        val file =
            myFixture.addFileToProject(
                "src/main/java/example/Changing.java",
                "package example; class Changing {}",
            )
        val index = ProjectRoleIndex.getInstance(project)
        index.getOrBuild(config())
        val changedRole = config().copy(roles = mapOf("changed" to role("Changed")))
        val before = index.getOrBuild(changedRole)
        assertFalse("example.Changed" in before.classToRole)

        val manager = PsiDocumentManager.getInstance(project)
        val document = requireNotNull(manager.getDocument(file))
        WriteCommandAction.runWriteCommandAction(project) {
            document.setText("package example; class Changed {}")
            manager.commitDocument(document)
        }

        val after = index.getOrBuild(changedRole)
        assertEquals("changed", after.classToRole["example.Changed"])
        assertFalse("example.Changing" in after.classToFilePath)
    }

    fun testProjectRootModificationInvalidatesSnapshot() {
        addMixedSources()
        val additionalRoot = myFixture.tempDirFixture.findOrCreateDir("additional-source-root")
        val index = ProjectRoleIndex.getInstance(project)
        val before = index.getOrBuild(config())
        val tracker = ProjectRootModificationTracker.getInstance(project)
        val countBefore = tracker.modificationCount

        ApplicationManager.getApplication().runWriteAction {
            val model = ModuleRootManager.getInstance(myFixture.module).modifiableModel
            try {
                model.contentEntries.first().addSourceFolder(additionalRoot, false)
                model.commit()
            } finally {
                if (!model.isDisposed) model.dispose()
            }
        }

        assertTrue(tracker.modificationCount > countBefore)
        val after = index.getOrBuild(config())
        assertNotSame(before, after)
        assertEquals(before.classToFilePath, after.classToFilePath)
    }

    private fun role(suffix: String): Role = Role(description = null, priority = 1, match = Matcher.ClassNameEndsWith(suffix))

    private fun addMixedSources() {
        myFixture.addFileToProject(
            "src/main/java/example/JavaComponent.java",
            "package example; public class JavaComponent {}",
        )
        myFixture.addFileToProject(
            "src/main/kotlin/example/KotlinComponent.kt",
            """
            package example

            class KotlinComponent {
                class Nested
            }
            """.trimIndent(),
        )
    }

    private fun config(): ShamashPsiConfigV1 =
        ShamashPsiConfigV1(
            version = 1,
            project =
                ProjectConfigV1(
                    rootPackage = null,
                    sourceGlobs = SourceGlobsV1(include = emptyList(), exclude = emptyList()),
                    validation = ValidationConfigV1(unknownRule = UnknownRulePolicyV1.IGNORE),
                ),
            roles = emptyMap(),
            rules = emptyList(),
            shamashExceptions = emptyList(),
        )
}
