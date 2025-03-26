package com.example.myapplication

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual

val json = Json {
    ignoreUnknownKeys = true
    serializersModule = SerializersModule {
        contextual(JsonElementSerializer) // 注册全局序列化器
    }
}

object JsonElementSerializer : KSerializer<JsonElement> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("JsonElement", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: JsonElement) {
        val jsonString = value.toString()
        encoder.encodeString(jsonString)
    }

    override fun deserialize(decoder: Decoder): JsonElement {
        val jsonString = decoder.decodeString()
        return Json.parseToJsonElement(jsonString)
    }
}