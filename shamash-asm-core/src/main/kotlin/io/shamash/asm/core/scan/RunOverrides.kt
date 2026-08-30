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

import io.shamash.asm.core.config.schema.v1.model.BaselineMode
import io.shamash.asm.core.config.schema.v1.model.ScanScope

/**
 * Non-persistent overrides applied to a single scan.
 * YAML remains the canonical persisted configuration.
 */
data class RunOverrides(
    val scan: ScanOverrides? = null,
    val runner: RunnerOverrides? = null,
)

data class ScanOverrides(
    val scope: ScanScope? = null,
    val followSymlinks: Boolean? = null,
    val maxClasses: Int? = null,
    val maxJarBytes: Int? = null,
    val maxClassBytes: Int? = null,
)

/** Runner/engine switches that must never mutate the project configuration. */
data class RunnerOverrides(
    /** Override baseline behavior for this run (used by `shamash baseline create`). */
    val baselineMode: BaselineMode? = null,
    /** Override report export for this run. Baseline generation is independent of report export. */
    val exportEnabled: Boolean? = null,
    /** Kept for source/binary compatibility with early 0.x callers. */
    val _reserved: Int = 0,
)
