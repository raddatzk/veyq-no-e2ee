package app.veyq.crypto

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * One shared HTTP client + the common Bearer-authenticated send, so each feature object doesn't spin up its
 * own `HttpClient` (a connection pool + threads) and re-implement the request/error boilerplate. Error
 * messages carry only the status code, never the response body (operator-blind: bodies stay out of logs).
 */
object ApiClient {
    /** The one shared HTTP client. Files with bespoke request shapes use it directly; most go through ok()/send(). */
    val shared: HttpClient = HttpClient.newHttpClient()
    private val client: HttpClient get() = shared

    /** Raw send — the caller inspects the status (used by endpoints with 204 success or special codes).
     *  A null [token] omits the Authorization header (the unauthenticated setup/register/login/reset flows). */
    fun send(apiBase: String, token: String?, path: String, method: String, body: String? = null): HttpResponse<String> {
        val b = HttpRequest.newBuilder(URI.create("$apiBase$path"))
        if (token != null) b.header("Authorization", "Bearer $token")
        val pub = if (body != null) {
            b.header("Content-Type", "application/json")
            HttpRequest.BodyPublishers.ofString(body)
        } else {
            HttpRequest.BodyPublishers.noBody()
        }
        return client.send(b.method(method, pub).build(), HttpResponse.BodyHandlers.ofString())
    }

    /** Send and require 200, returning the body; [label] names the call for the error message (no payload). */
    fun ok(apiBase: String, token: String?, path: String, method: String, body: String? = null, label: String): String {
        val resp = send(apiBase, token, path, method, body)
        if (resp.statusCode() != 200) throw RuntimeException("$label failed: ${resp.statusCode()}")
        return resp.body()
    }
}
