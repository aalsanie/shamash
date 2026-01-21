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
package io.shamash.asm.core.scan

import io.shamash.asm.core.config.schema.v1.model.ScanScope

/**
 * Non-persistent overrides applied at runtime.
 *
 * YAML remains the canonical configuration. These values are applied in-memory
 * for a single run (CLI flags / IDE run settings).
 */
data class RunOverrides(
    val scan: ScanOverrides? = null,
    val runner: RunnerOverrides? = null,
)

/**
 * Overrides for scan knobs.
 */
data class ScanOverrides(
    val scope: ScanScope? = null,
    val followSymlinks: Boolean? = null,
    val maxClasses: Int? = null,
    val maxJarBytes: Int? = null,
    val maxClassBytes: Int? = null,
)

/**
 * Reserved for future runner/engine toggles.
 */
data class RunnerOverrides(
    val _reserved: Int = 0,
)
