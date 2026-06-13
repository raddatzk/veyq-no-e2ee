package app.veyq.crypto

import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * uc.admin.invite-members-roles (Android) — existing-user direct grant. A key-holding Tenant Owner
 * reads their own scope grant, unwraps the scope private key, re-wraps it to an existing user's
 * published identity public key (from the key directory) and assigns a role. The server stays blind.
 */
object InviteMembers {
    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val dec = Base64.getUrlDecoder()
    private val client = HttpClient.newHttpClient()

    /** Look up a user's published identity public key by email (the key directory). */
    fun lookupDirectoryEntry(apiBase: String, token: String, email: String): JSONObject {
        val q = URLEncoder.encode(email, StandardCharsets.UTF_8)
        val req = HttpRequest.newBuilder(URI.create("$apiBase/api/directory/users?email=$q"))
            .header("Authorization", "Bearer $token").GET().build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 200) throw RuntimeException("directory lookup failed: ${resp.statusCode()} ${resp.body()}")
        return JSONObject(resp.body())
    }

    /** Grant an existing user (by email) access to the tenant scope + a role via a direct wrap. */
    fun grantScopeToMember(
        apiBase: String, owner: Login.UnlockedSession, tenantId: String, recipientEmail: String, role: String,
    ): JSONObject {
        // 1. read our own grant and recover the scope private key (raw serialized bytes)
        val myGrant = Tenant.getMyScopeGrant(apiBase, owner.sessionToken, tenantId)
        val ownerPkX = owner.kemPublicKey.copyOfRange(0, 32)
        val ownerPkM = owner.kemPublicKey.copyOfRange(32, owner.kemPublicKey.size)
        val wrapped = dec.decode(myGrant.getJSONObject("wrappedScopePrivateKey").getString("bytes"))
        val scopePrivateBytes = V1.asymUnwrap(owner.identity.kemX, owner.identity.mlkemSeed, ownerPkX, ownerPkM, wrapped, "scope-grant")

        // 2. look up the recipient and re-wrap the scope private key to their identity public key
        val recipient = lookupDirectoryEntry(apiBase, owner.sessionToken, recipientEmail)
        val rKem = dec.decode(recipient.getJSONObject("identityPublicKey").getString("kemPublicKey"))
        val rewrapped = V1.asymWrap(rKem.copyOfRange(0, 32), rKem.copyOfRange(32, rKem.size), scopePrivateBytes, "scope-grant", V1.randomBytes(12))

        // 3. hand the server ciphertext + the recipient + the role
        val json = """{"recipientUserId":"${recipient.getString("userId")}","role":"$role",""" +
            """"wrappedScopePrivateKey":{"suiteId":"v1","bytes":"${enc.encodeToString(rewrapped)}"}}"""
        val req = HttpRequest.newBuilder(URI.create("$apiBase/api/tenants/$tenantId/grants"))
            .header("Authorization", "Bearer ${owner.sessionToken}")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 200) throw RuntimeException("grant failed: ${resp.statusCode()} ${resp.body()}")
        return JSONObject(resp.body())
    }
}
