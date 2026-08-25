package com.haoai.agent.data

import kotlinx.serialization.json.Json

object HaoJson {
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
        explicitNulls = false
    }
}
