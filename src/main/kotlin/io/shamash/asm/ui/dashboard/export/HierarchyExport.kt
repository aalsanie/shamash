package io.shamash.asm.ui.dashboard.export

import com.intellij.openapi.project.Project
import io.shamash.asm.model.AsmClassInfo

object HierarchyExport {

    private const val SCHEMA = "shamash.hierarchy.v1"

    fun exportSnapshot(
        project: Project,
        format: ExportUtil.Format,
        info: AsmClassInfo,
        transitiveSubtypes: Boolean,
        superChainFqcn: List<String>,
        interfacesFqcn: List<String>,
        subtypesFqcn: List<String>
    ) {
        val safeName = sanitizeFileName(info.fqcn)
        val suggested = "shamash-hierarchy-$safeName.${format.ext}"

        val content = when (format) {
            ExportUtil.Format.JSON -> buildJson(
                schema = SCHEMA,
                info = info,
                transitiveSubtypes = transitiveSubtypes,
                superChain = superChainFqcn,
                ifaces = interfacesFqcn,
                subtypes = subtypesFqcn
            )

            ExportUtil.Format.XML -> buildXml(
                schema = SCHEMA,
                info = info,
                transitiveSubtypes = transitiveSubtypes,
                superChain = superChainFqcn,
                ifaces = interfacesFqcn,
                subtypes = subtypesFqcn
            )
        }

        ExportUtil.saveWithDialog(
            project = project,
            title = "Export Shamash Hierarchy",
            description = "Export current hierarchy snapshot.",
            format = format,
            suggestedFileName = suggested,
            content = content
        )
    }

    private fun buildJson(
        schema: String,
        info: AsmClassInfo,
        transitiveSubtypes: Boolean,
        superChain: List<String>,
        ifaces: List<String>,
        subtypes: List<String>
    ): String {
        fun jsonStr(s: String): String = "\"" + s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t") + "\""

        fun jsonArr(xs: List<String>): String = xs.joinToString(prefix = "[", postfix = "]") { jsonStr(it) }

        return buildString {
            append("{")
            append("\"schema\":").append(jsonStr(schema)).append(',')
            append("\"selected\":").append(jsonStr(info.fqcn)).append(',')
            append("\"module\":").append(if (info.moduleName == null) "null" else jsonStr(info.moduleName!!)).append(',')
            append("\"origin\":").append(jsonStr(info.originDisplayName)).append(',')
            append("\"transitiveSubtypes\":").append(transitiveSubtypes).append(',')
            append("\"superChain\":").append(jsonArr(superChain)).append(',')
            append("\"interfaces\":").append(jsonArr(ifaces)).append(',')
            append("\"subtypes\":").append(jsonArr(subtypes))
            append("}")
        }
    }

    private fun buildXml(
        schema: String,
        info: AsmClassInfo,
        transitiveSubtypes: Boolean,
        superChain: List<String>,
        ifaces: List<String>,
        subtypes: List<String>
    ): String {
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&apos;")

        return buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
            append("""<shamashHierarchy schema="${esc(schema)}" selected="${esc(info.fqcn)}" """)
            append("""origin="${esc(info.originDisplayName)}" """)
            append("""module="${esc(info.moduleName ?: "")}" """)
            append("""transitiveSubtypes="$transitiveSubtypes">""").append('\n')

            append("  <superChain>\n")
            superChain.forEach { append("""    <type fqcn="${esc(it)}" />""").append('\n') }
            append("  </superChain>\n")

            append("  <interfaces>\n")
            ifaces.forEach { append("""    <type fqcn="${esc(it)}" />""").append('\n') }
            append("  </interfaces>\n")

            append("  <subtypes>\n")
            subtypes.forEach { append("""    <type fqcn="${esc(it)}" />""").append('\n') }
            append("  </subtypes>\n")

            append("</shamashHierarchy>\n")
        }
    }

    private fun sanitizeFileName(fqcn: String): String =
        fqcn.replace('/', '.')
            .replace(':', '_')
            .replace('<', '_')
            .replace('>', '_')
            .replace('*', '_')
            .replace('?', '_')
            .replace('"', '_')
            .replace('|', '_')
}
