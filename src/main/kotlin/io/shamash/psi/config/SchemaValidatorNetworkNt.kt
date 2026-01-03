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
package io.shamash.psi.config

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
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
    private val mapper: ObjectMapper = ObjectMapper()

    private val schema: JsonSchema by lazy(LazyThreadSafetyMode.PUBLICATION) {
        SchemaResources.openSchemaJson().use { stream ->
            loadSchema(stream)
        }
    }

    override fun validate(raw: Any?): List<ValidationError> {
        ProgressManager.checkCanceled()

        val node: JsonNode =
            try {
                toJsonNode(raw)
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: Exception) {
                return listOf(
                    ValidationError(
                        path = "",
                        message = "Config root cannot be converted to JSON node: ${e.message}",
                        severity = ValidationSeverity.ERROR,
                    ),
                )
            }

        ProgressManager.checkCanceled()

        val messages: Set<ValidationMessage> =
            try {
                schema.validate(node)
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: Exception) {
                return listOf(
                    ValidationError(
                        path = "",
                        message = "Schema validation failed unexpectedly: ${e.message}",
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
        ProgressManager.checkCanceled()
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
        val msg: String = extractBestEffortMessage(this)

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

        return if (dollarCleaned.startsWith("#/")) {
            dollarCleaned.removePrefix("#/")
        } else {
            dollarCleaned
        }
    }

    private fun extractBestEffortPath(msg: ValidationMessage): String {
        // networknt versions differ; use reflection for best compatibility
        val instanceLocation = reflectToString(msg, "getInstanceLocation")
        if (instanceLocation.isNotBlank()) return instanceLocation

        val evaluationPath = reflectToString(msg, "getEvaluationPath")
        if (evaluationPath.isNotBlank()) return evaluationPath

        val schemaLocation = reflectToString(msg, "getSchemaLocation")
        if (schemaLocation.isNotBlank()) return schemaLocation

        return ""
    }

    private fun extractBestEffortMessage(msg: ValidationMessage): String {
        val m = msg.message
        return if (!m.isNullOrBlank()) m else "Schema validation error"
    }

    private fun reflectToString(
        target: Any,
        methodName: String,
    ): String {
        return try {
            val m: Method =
                target.javaClass.methods.firstOrNull {
                    it.name == methodName && it.parameterCount == 0
                } ?: return ""
            val r = m.invoke(target) ?: return ""
            r.toString()
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (_: Throwable) {
            ""
        }
    }
}
