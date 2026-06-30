package com.veyq.api.auth

import io.smallrye.config.ConfigMapping
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.jboss.logging.Logger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat

const val PASSWORD_COMPROMISED_CODE = "PASSWORD_COMPROMISED"

interface PasswordCompromiseChecker {
    fun check(rawPassword: String)
}

@ConfigMapping(prefix = "veyq.auth.pwned-passwords")
interface PwnedPasswordsConfig {
    fun enabled(): Boolean
    fun endpoint(): String
    fun timeoutMs(): Long
    fun failClosed(): Boolean
}

@ApplicationScoped
class HaveIBeenPwnedPasswordCompromiseChecker @Inject constructor(
    private val config: PwnedPasswordsConfig,
    private val rangeClient: PwnedPasswordsRangeClient,
) : PasswordCompromiseChecker {
    private val log: Logger = Logger.getLogger(HaveIBeenPwnedPasswordCompromiseChecker::class.java)

    override fun check(rawPassword: String) {
        if (!config.enabled()) return

        val hash = sha1Hex(rawPassword)
        val prefix = hash.take(5)
        val suffix = hash.drop(5)

        val body = try {
            rangeClient.fetchRange(prefix)
        } catch (exception: Exception) {
            if (config.failClosed()) {
                throw BadRequestException("Password compromise check is unavailable", "PASSWORD_COMPROMISE_CHECK_UNAVAILABLE")
            }
            log.warnf(
                "Pwned Passwords check failed; allowing password because failClosed=false: %s",
                exception.message ?: exception::class.java.simpleName,
            )
            return
        }

        val compromised = body.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .any { line -> line.substringBefore(':').equals(suffix, ignoreCase = true) }

        if (compromised) {
            throw BadRequestException("Password appears in known breaches", PASSWORD_COMPROMISED_CODE)
        }
    }

    private fun sha1Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(value.toByteArray(Charsets.UTF_8))
        return HexFormat.of().formatHex(digest).uppercase()
    }
}

interface PwnedPasswordsRangeClient {
    fun fetchRange(prefix: String): String
}

@ApplicationScoped
class JavaNetPwnedPasswordsRangeClient @Inject constructor(
    private val config: PwnedPasswordsConfig,
) : PwnedPasswordsRangeClient {
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(config.timeoutMs().coerceAtLeast(1)))
        .build()

    override fun fetchRange(prefix: String): String {
        val endpoint = config.endpoint().trimEnd('/')
        val request = HttpRequest.newBuilder(URI.create("$endpoint/$prefix"))
            .timeout(Duration.ofMillis(config.timeoutMs().coerceAtLeast(1)))
            .header("User-Agent", "veyq")
            .header("Add-Padding", "true")
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            throw IllegalStateException("Pwned Passwords API returned ${response.statusCode()}")
        }
        return response.body()
    }
}
