package com.agenthub.core

import kotlin.io.encoding.Base64
import kotlinx.serialization.json.*

/** Consumes the repository schema; versioned domain JSON is separate from provider wire. */
object DomainContract {
    private val validator by lazy { SchemaValidator(managementSchemaBase64) }
    fun validate(json: String, definition: String): String? = validator.validate(json, definition)
}
object CodexHttpContract {
    private val validator by lazy { SchemaValidator(codexHttpSchemaBase64) }
    fun validate(json: String, definition: String): String? = validator.validate(json, definition)
}
object CodexDiscoveryContract {
    private val validator by lazy { SchemaValidator(codexDiscoverySchemaBase64) }
    fun validate(json: String): String? = validator.validateDocument(json)
    fun validateNamed(json: String, definition: String): String? = validator.validate(json, definition)
}
internal class SchemaValidator(encoded: String) {
    private val schema = Json.parseToJsonElement(Base64.decode(encoded).decodeToString()).jsonObject
    fun validateDocument(json: String): String? = try { validateValue(Json.parseToJsonElement(json), schema, "$") } catch (_: Exception) { "invalid-json" }
    fun validate(json: String, definition: String): String? { return try {
        val rule = schema["definitions"]!!.jsonObject[definition]?.jsonObject
            ?: return "unknown-definition"
        validateValue(Json.parseToJsonElement(json), rule, "$")
    } catch (_: Exception) { "invalid-json" } }

    private fun validateValue(value: JsonElement, rule: JsonObject, path: String): String? {
        rule["\$ref"]?.jsonPrimitive?.content?.let { reference ->
            return validateValue(value, schema["definitions"]!!.jsonObject.getValue(reference.removePrefix("#/definitions/")).jsonObject, path)
        }
        rule["oneOf"]?.jsonArray?.let { alternatives ->
            return if (alternatives.count { validateValue(value, it.jsonObject, path) == null } == 1) null else "$path:oneOf"
        }
        if (rule["const"]?.let { constant ->
            if (constant is JsonPrimitive && value is JsonPrimitive && !constant.isString && !value.isString &&
                constant.doubleOrNull != null && value.doubleOrNull != null) constant.double != value.double else constant != value
        } == true) return "$path:const"
        if (rule["enum"]?.jsonArray?.let { value !in it } == true) return "$path:enum"
        when (rule["type"]?.jsonPrimitive?.content) {
            "object" -> {
                if (value !is JsonObject) return "$path:object"
                val properties = rule["properties"]?.jsonObject ?: JsonObject(emptyMap())
                if (rule["additionalProperties"] == JsonPrimitive(false) && value.keys.any { it !in properties }) return "$path:unknown-field"
                if (rule["required"]?.jsonArray?.any { it.jsonPrimitive.content !in value } == true) return "$path:missing-field"
                for ((name, item) in value) {
                    val childRule = properties[name]?.jsonObject ?: (rule["additionalProperties"] as? JsonObject) ?: continue
                    validateValue(item, childRule, "$path.$name")?.let { return it }
                }
            }
            "array" -> {
                if (value !is JsonArray) return "$path:array"
                if (value.size < (rule["minItems"]?.jsonPrimitive?.int ?: 0) || value.size > (rule["maxItems"]?.jsonPrimitive?.int ?: Int.MAX_VALUE)) return "$path:array-size"
                if (rule["uniqueItems"] == JsonPrimitive(true) && value.distinct().size != value.size) return "$path:duplicate-item"
                rule["items"]?.jsonObject?.let { itemRule ->
                    value.forEachIndexed { index, item -> validateValue(item, itemRule, "$path[$index]")?.let { return it } }
                }
            }
            "string" -> {
                if (value !is JsonPrimitive || !value.isString) return "$path:string"
                // JSON Schema lengths count Unicode scalars, not UTF-16 code units.
                val length = value.content.count { !it.isLowSurrogate() }
                if (length < (rule["minLength"]?.jsonPrimitive?.int ?: 0)) return "$path:string-size"
                if (length > (rule["maxLength"]?.jsonPrimitive?.int ?: Int.MAX_VALUE)) return "$path:string-size"
                rule["pattern"]?.jsonPrimitive?.content?.let { if (!Regex(it).containsMatchIn(value.content)) return "$path:pattern" }
                if (rule["format"]?.jsonPrimitive?.content == "uri" && (!Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(value.content) || value.content.any { it.code in 0x09..0x0D || it.code in 0x2000..0x200A || it.code in listOf(0x20, 0xA0, 0x1680, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000, 0xFEFF) })) return "$path:uri"
            }
            "number" -> {
                val primitive = value as? JsonPrimitive ?: return "$path:number"
                val number = primitive.takeUnless { it.isString }?.doubleOrNull ?: return "$path:number"
                if (!number.isFinite()) return "$path:number"
                if (number < (rule["minimum"]?.jsonPrimitive?.double ?: -Double.MAX_VALUE)) return "$path:minimum"
                if (number > (rule["maximum"]?.jsonPrimitive?.double ?: Double.MAX_VALUE)) return "$path:maximum"
            }
            "integer" -> {
                val primitive = value as? JsonPrimitive ?: return "$path:integer"
                if (primitive.isString) return "$path:integer"
                val number = primitive.doubleOrNull ?: return "$path:integer"
                if (!number.isFinite() || number % 1.0 != 0.0 || kotlin.math.abs(number) > 9007199254740991.0) return "$path:integer"
                if (number < (rule["minimum"]?.jsonPrimitive?.double ?: -9007199254740991.0)) return "$path:minimum"
                if (number > (rule["maximum"]?.jsonPrimitive?.double ?: 9007199254740991.0)) return "$path:maximum"
            }
            "boolean" -> if (value !is JsonPrimitive || value.isString || value.booleanOrNull == null) return "$path:boolean"
        }
        return null
    }
}
