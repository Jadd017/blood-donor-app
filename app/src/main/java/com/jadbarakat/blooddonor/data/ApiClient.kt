package com.jadbarakat.blooddonor.data

import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.request
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.serialization.ContentConvertException
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Everything that can go wrong when talking to our server. */
sealed class ApiException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** The server answered with an error body: {"error": code, "message": text}. */
    class Http(val status: Int, val code: String, message: String) : ApiException("$status $code: $message")

    /** The server could not be reached (offline, server down, adb reverse missing, timeout). */
    class Unreachable(cause: Throwable) : ApiException("Server unreachable", cause)

    /** No Firebase user is signed in, so there is no token to send. */
    class NotSignedIn : ApiException("Not signed in")

    /** Anything else, e.g. a response we couldn't parse. */
    class Unexpected(cause: Throwable) : ApiException(cause.message ?: "Unexpected error", cause)
}

/**
 * Thin wrapper over Ktor's HttpClient for our API.
 *
 * Authenticated requests carry the Firebase ID token as a Bearer token. If the server rejects it
 * (401, or 403 email_not_verified because the cached token predates email verification), the token
 * is refreshed once and the request retried.
 */
class ApiClient(
    baseUrl: String,
    private val auth: FirebaseAuth,
) {
    internal val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private val http = HttpClient(OkHttp) {
        engine {
            config {
                connectTimeout(10, TimeUnit.SECONDS)
                readTimeout(20, TimeUnit.SECONDS)
            }
        }
        install(ContentNegotiation) { json(json) }
        defaultRequest { url(baseUrl) }
    }

    /**
     * Sends a request set up by [configure], checks for a 2xx and decodes it with [read]; throws [ApiException] otherwise.
     * Paths are relative to the base URL, e.g. "api/v1/me".
     */
    suspend fun <T> send(
        authenticated: Boolean,
        configure: HttpRequestBuilder.() -> Unit,
        read: suspend (HttpResponse) -> T,
    ): T = translateErrors {
        val response = if (authenticated) sendWithToken(configure) else http.request(configure).orThrow()
        read(response)
    }

    // Not named `build`: inside http.request { } that would resolve to HttpRequestBuilder.build(),
    // silently dropping the URL and sending the request to http://localhost:80.
    private suspend fun sendWithToken(configure: HttpRequestBuilder.() -> Unit): HttpResponse {
        val user = auth.currentUser ?: throw ApiException.NotSignedIn()
        val first = http.request {
            configure()
            bearerAuth(user.getIdToken(false).await().token.orEmpty())
        }
        if (first.status.isSuccess()) return first

        val error = first.toError()
        if (!error.isTokenProblem()) throw error
        return http.request {
            configure()
            bearerAuth(user.getIdToken(true).await().token.orEmpty())
        }.orThrow()
    }

    private suspend fun HttpResponse.orThrow(): HttpResponse =
        if (status.isSuccess()) this else throw toError()

    private suspend fun HttpResponse.toError(): ApiException.Http {
        val text = runCatching { bodyAsText() }.getOrDefault("")
        val body = try {
            json.decodeFromString<ErrorBody>(text)
        } catch (e: SerializationException) {
            ErrorBody("http_${status.value}", text.take(200))
        } catch (e: IllegalArgumentException) {
            ErrorBody("http_${status.value}", text.take(200))
        }
        return ApiException.Http(status.value, body.error, body.message)
    }

    private fun ApiException.Http.isTokenProblem(): Boolean =
        status == HttpStatusCode.Unauthorized.value ||
            (status == HttpStatusCode.Forbidden.value && code == ErrorCodes.EMAIL_NOT_VERIFIED)

    private suspend fun <T> translateErrors(block: suspend () -> T): T = try {
        block()
    } catch (e: ApiException) {
        throw e
    } catch (e: IOException) {
        // Includes ConnectException, timeouts and Ktor's HttpRequestTimeoutException.
        throw ApiException.Unreachable(e)
    } catch (e: FirebaseNetworkException) {
        throw ApiException.Unreachable(e)
    } catch (e: SerializationException) {
        throw ApiException.Unexpected(e)
    } catch (e: ContentConvertException) {
        throw ApiException.Unexpected(e)
    }
}
