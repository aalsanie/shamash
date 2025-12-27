/*
 * Copyright © 2025-2026 | Shamash is a refactoring tool that enforces clean architecture.
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
package io.shamash.asm.ui.dashboard.export

import com.intellij.openapi.project.Project

object HotspotsExport {
    private const val SCHEMA = "shamash.hotspots.v1"

    data class HotspotRow(
        val fqcn: String,
        val score: Int,
        val depth: Int,
        val methods: Int,
        val publicMethods: Int,
        val fields: Int,
        val instructions: Int,
        val fanOut: Int,
        val fanIn: Int,
        val reason: String,
    )

    fun exportView(
        project: Project,
        format: ExportUtil.Format,
        mode: String,
        rows: List<HotspotRow>,
    ) {
        if (rows.isEmpty()) return

        val suggested = "shamash-hotspots-$mode.${format.ext}"

        val content =
            when (format) {
                ExportUtil.Format.JSON -> toJson(schema = SCHEMA, mode = mode, rows = rows)
                ExportUtil.Format.XML -> toXml(schema = SCHEMA, mode = mode, rows = rows)
            }

        ExportUtil.saveWithDialog(
            project = project,
            title = "Export Shamash Hotspots",
            description = "Export current hotspots view (mode=$mode).",
            format = format,
            suggestedFileName = suggested,
            content = content,
        )
    }

    private fun toJson(
        schema: String,
        mode: String,
        rows: List<HotspotRow>,
    ): String {
        fun jsonStr(s: String): String =
            "\"" +
                s
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t") + "\""

        return buildString {
            append("{")
            append("\"schema\":").append(jsonStr(schema)).append(',')
            append("\"mode\":").append(jsonStr(mode)).append(',')
            append("\"count\":").append(rows.size).append(',')
            append("\"rows\":[")
            for (i in rows.indices) {
                if (i > 0) append(',')
                val r = rows[i]
                append("{")
                append("\"fqcn\":").append(jsonStr(r.fqcn)).append(',')
                append("\"score\":").append(r.score).append(',')
                append("\"depth\":").append(r.depth).append(',')
                append("\"methods\":").append(r.methods).append(',')
                append("\"publicMethods\":").append(r.publicMethods).append(',')
                append("\"fields\":").append(r.fields).append(',')
                append("\"instructions\":").append(r.instructions).append(',')
                append("\"fanOut\":").append(r.fanOut).append(',')
                append("\"fanIn\":").append(r.fanIn).append(',')
                append("\"reason\":").append(jsonStr(r.reason))
                append("}")
            }
            append("]}")
        }
    }

    private fun toXml(
        schema: String,
        mode: String,
        rows: List<HotspotRow>,
    ): String {
        fun esc(s: String) =
            s
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;")

        return buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
            append("""<shamashHotspots schema="${esc(schema)}" mode="${esc(mode)}" count="${rows.size}">""").append('\n')
            for (r in rows) {
                append("""  <row fqcn="${esc(r.fqcn)}" score="${r.score}" depth="${r.depth}" """)
                append("""methods="${r.methods}" publicMethods="${r.publicMethods}" fields="${r.fields}" """)
                append("""instructions="${r.instructions}" fanOut="${r.fanOut}" fanIn="${r.fanIn}">""")
                append('\n')
                append("    <reason>").append(esc(r.reason)).append("</reason>\n")
                append("  </row>\n")
            }
            append("</shamashHotspots>\n")
        }
    }
}
