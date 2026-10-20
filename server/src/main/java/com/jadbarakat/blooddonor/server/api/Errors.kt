package com.jadbarakat.blooddonor.server.api

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable

/**
 * Every error response has this shape: {"error": "<code>", "message": "<text>"}, plus optional
 * [details] the app can use in its own message (e.g. {"until": "2026-06-22T09:00:00Z"}).
 */
@Serializable
data class ApiError(val error: String, val message: String, val details: Map<String, String>? = null)

/** Thrown anywhere in request handling; StatusPages turns it into an [ApiError] response. */
class ApiException(
    val status: HttpStatusCode,
    val code: String,
    override val message: String,
    val details: Map<String, String>? = null,
) : RuntimeException(message)

fun badRequest(code: String, message: String) = ApiException(HttpStatusCode.BadRequest, code, message)
