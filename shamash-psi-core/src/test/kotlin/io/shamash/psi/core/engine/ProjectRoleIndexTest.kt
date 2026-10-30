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

import com.intellij.testFramework.builders.EmptyModuleFixtureBuilder
import com.intellij.testFramework.fixtures.CodeInsightFixtureTestCase
import io.shamash.psi.core.config.schema.v1.model.ProjectConfigV1
import io.shamash.psi.core.config.schema.v1.model.ShamashPsiConfigV1
import io.shamash.psi.core.config.schema.v1.model.SourceGlobsV1
import io.shamash.psi.core.config.schema.v1.model.UnknownRulePolicyV1
import io.shamash.psi.core.config.schema.v1.model.ValidationConfigV1
import io.shamash.psi.core.engine.index.ProjectRoleIndex
import kotlin.test.assertFalse
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
