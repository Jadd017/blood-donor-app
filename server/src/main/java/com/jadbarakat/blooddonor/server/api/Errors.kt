package com.jadbarakat.blooddonor.server.api

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable

/** Every error response has this shape: {"error": "<code>", "message": "<text>"}. */
@Serializable
data class ApiError(val error: String, val message: String)

/** Thrown anywhere in request handling; StatusPages turns it into an [ApiError] response. */
class ApiException(
    val status: HttpStatusCode,
    val code: String,
    override val message: String,
) : RuntimeException(message)

fun badRequest(code: String, message: String) = ApiException(HttpStatusCode.BadRequest, code, message)
