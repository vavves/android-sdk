package com.qonversion.android.sdk.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class PropertiesRequest (
    @Json(name = "d") val d: Environment,
    @Json(name = "v") val v: String,
    @Json(name = "access_token") val accessToken: String,
    @Json(name = "client_uid") val clientUid: String?,
    @Json(name = "properties") val properties: Map<String, String>
)