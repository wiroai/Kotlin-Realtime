package ai.wiro.realtime.networking

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

internal fun Map<String, Any?>.toJsonObject(): JsonObject = JsonObject(
    mapValues { (_, value) -> value.toJsonElement() },
)

internal fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull

    is JsonElement -> this

    is Boolean -> JsonPrimitive(this)

    is Int -> JsonPrimitive(this)

    is Long -> JsonPrimitive(this)

    is Float -> JsonPrimitive(this)

    is Double -> JsonPrimitive(this)

    is String -> JsonPrimitive(this)

    is Map<*, *> -> JsonObject(
        entries.associate { (key, value) ->
            key.toString() to value.toJsonElement()
        },
    )

    is List<*> -> JsonArray(map { it.toJsonElement() })

    else -> JsonPrimitive(toString())
}

internal fun JsonElement.toPlain(): Any? = when (this) {
    is JsonNull -> null
    is JsonPrimitive -> primitiveValue()
    is JsonArray -> map { it.toPlain() }
    is JsonObject -> mapValues { it.value.toPlain() }
}

private fun JsonPrimitive.primitiveValue(): Any {
    booleanOrNull?.let { return it }
    if (!isString) {
        longOrNull?.let { return it }
        doubleOrNull?.let { return it }
    }
    return contentOrNull ?: content
}
