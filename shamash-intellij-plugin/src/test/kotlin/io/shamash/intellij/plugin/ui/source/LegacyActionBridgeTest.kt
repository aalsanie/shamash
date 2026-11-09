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
package io.shamash.intellij.plugin.ui.source

import com.intellij.openapi.project.Project
import java.lang.reflect.Proxy
import kotlin.test.Test

class LegacyActionBridgeTest {
    @Test
    fun `disposed projects are ignored by both compatibility entry points`() {
        val disposed =
            Proxy.newProxyInstance(
                Project::class.java.classLoader,
                arrayOf(Project::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "isDisposed" -> true
                    else -> error("Unexpected project access: ${method.name}")
                }
            } as Project
        LegacyActionBridge.openFindings(disposed)
        LegacyActionBridge.runSourceAnalysis(disposed)
    }
}
