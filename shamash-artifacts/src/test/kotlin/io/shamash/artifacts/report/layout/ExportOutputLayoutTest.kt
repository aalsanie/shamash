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
package io.shamash.artifacts.report.layout

import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals

class ExportOutputLayoutTest {
    @Test
    fun normalizeOutputDir_usesDefaultWhenNull() {
        val base = Paths.get("/tmp/project")
        val out = ExportOutputLayout.normalizeOutputDir(base, null)
        assertEquals(base.resolve(ExportOutputLayout.DEFAULT_DIR_NAME).normalize(), out)
    }

    @Test
    fun normalizeOutputDir_respectsProvidedOutputDir() {
        val base = Paths.get("/tmp/project")
        val provided = Paths.get("/tmp/project/out/../out2")
        val out = ExportOutputLayout.normalizeOutputDir(base, provided)
        assertEquals(provided.normalize(), out)
    }
}
