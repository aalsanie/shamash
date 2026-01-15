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
package io.shamash.psi.core.config

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import com.networknt.schema.ValidationMessage
import java.io.InputStream
import java.lang.reflect.Method

/**
 * Structural validation of PSI YAML (parsed to Map/List primitives)
 * against JSON Schema stored in resources.
 *
 * NOTE: Despite the name, this validator does NOT perform network I/O.
 * It uses networknt-json-schema (the library), not the network.
 */
object SchemaValidatorNetworkNt : SchemaValidator {
    /**
     * Optional cancellation hook.
     * IntelliJ can set this to ProgressManager::checkCanceled via wiring in plugin code.
     * CLI can leave it null.
     */
    @Volatile
    var cancelCheck: (() -> Unit)? = null

    private val mapper: ObjectMapper = ObjectMapper()

    private val schema: JsonSchema by lazy(LazyThreadSafetyMode.PUBLICATION) {
        SchemaResources.openSchemaJson().use { stream -> loadSchema(stream) }
    }

    override fun validate(raw: Any?): List<ValidationError> {
        cancelCheck?.invoke()

        val node: JsonNode =
            try {
                toJsonNode(raw)
            } catch (e: Exception) {
                return listOf(
                    ValidationError(
                        path = "",
                        message = "Config root cannot be converted to JSON node: ${e.message ?: e::class.java.simpleName}",
                        severity = ValidationSeverity.ERROR,
                    ),
                )
            }

        cancelCheck?.invoke()

        val messages: Set<ValidationMessage> =
            try {
                schema.validate(node)
            } catch (e: Exception) {
                return listOf(
                    ValidationError(
                        path = "",
                        message = "Schema validation failed unexpectedly: ${e.message ?: e::class.java.simpleName}",
                        severity = ValidationSeverity.ERROR,
                    ),
                )
            }

        if (messages.isEmpty()) return emptyList()

        return messages
            .asSequence()
            .map { it.toValidationError() }
            .sortedWith(compareBy({ it.path }, { it.message }))
            .toList()
    }

    private fun loadSchema(schemaStream: InputStream): JsonSchema {
        cancelCheck?.invoke()
        val schemaNode: JsonNode = mapper.readTree(schemaStream)
        val factory: JsonSchemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
        return factory.getSchema(schemaNode)
    }

    private fun toJsonNode(value: Any?): JsonNode =
        when (value) {
            null -> JsonNodeFactory.instance.nullNode()
            is Map<*, *> -> {
                val obj = JsonNodeFactory.instance.objectNode()
                for ((k, v) in value) {
                    val key = k?.toString() ?: continue
                    obj.set<JsonNode>(key, toJsonNode(v))
                }
                obj
            }
            is List<*> -> {
                val arr = JsonNodeFactory.instance.arrayNode()
                for (item in value) arr.add(toJsonNode(item))
                arr
            }
            is String -> JsonNodeFactory.instance.textNode(value)
            is Boolean -> JsonNodeFactory.instance.booleanNode(value)
            is Int -> JsonNodeFactory.instance.numberNode(value)
            is Long -> JsonNodeFactory.instance.numberNode(value)
            is Float -> JsonNodeFactory.instance.numberNode(value)
            is Double -> JsonNodeFactory.instance.numberNode(value)
            is Number -> JsonNodeFactory.instance.numberNode(value.toDouble())
            else -> JsonNodeFactory.instance.textNode(value.toString())
        }

    private fun ValidationMessage.toValidationError(): ValidationError {
        val rawPath: String = extractBestEffortPath(this)
        val cleanedPath: String = cleanJsonPath(rawPath)
        val msg: String = message?.takeIf { it.isNotBlank() } ?: "Schema validation error"

        return ValidationError(
            path = cleanedPath,
            message = msg,
            severity = ValidationSeverity.ERROR,
        )
    }

    private fun cleanJsonPath(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""

        val dollarCleaned =
            trimmed
                .removePrefix("$.")
                .removePrefix("$")
                .trim()

        return if (dollarCleaned.startsWith("#/")) dollarCleaned.removePrefix("#/") else dollarCleaned
    }

    private fun extractBestEffortPath(msg: ValidationMessage): String {
        // networknt versions differ; use reflection for best compatibility
        reflectToString(msg, "getInstanceLocation").takeIf { it.isNotBlank() }?.let { return it }
        reflectToString(msg, "getEvaluationPath").takeIf { it.isNotBlank() }?.let { return it }
        reflectToString(msg, "getSchemaLocation").takeIf { it.isNotBlank() }?.let { return it }
        return ""
    }

    private fun reflectToString(
        target: Any,
        methodName: String,
    ): String {
        return try {
            val m: Method =
                target.javaClass.methods.firstOrNull { it.name == methodName && it.parameterCount == 0 } ?: return ""
            (m.invoke(target) ?: "").toString()
        } catch (_: Throwable) {
            ""
        }
    }
}
