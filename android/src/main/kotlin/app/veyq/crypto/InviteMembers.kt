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

    /** Remove a member (instant layer of AD-24): revoke the member's scope grant + sessions. No crypto. */
    fun removeMember(apiBase: String, ownerToken: String, tenantId: String, userId: String): JSONObject {
        val req = HttpRequest.newBuilder(URI.create("$apiBase/api/tenants/$tenantId/members/$userId"))
            .header("Authorization", "Bearer $ownerToken").DELETE().build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 200) throw RuntimeException("remove member failed: ${resp.statusCode()} ${resp.body()}")
        return JSONObject(resp.body())
    }

    data class MemberInvite(val inviteId: String, val bootstrapSecret: String, val tempPrivateKey: String)

    /**
     * Issue a temp-wrap invite for a NEW user. The owner unwraps their own scope private key, generates a
     * one-time temp keypair, and wraps the scope to the temp public key. The temp private key is returned
     * to ride the invite URL fragment; the server stores only the temp public key + ciphertext.
     */
    fun issueMemberInvite(apiBase: String, owner: Login.UnlockedSession, tenantId: String, role: String): MemberInvite {
        val myGrant = Tenant.getMyScopeGrant(apiBase, owner.sessionToken, tenantId)
        val ownerPkX = owner.kemPublicKey.copyOfRange(0, 32)
        val ownerPkM = owner.kemPublicKey.copyOfRange(32, owner.kemPublicKey.size)
        val wrapped = dec.decode(myGrant.getJSONObject("wrappedScopePrivateKey").getString("bytes"))
        val scopePrivateBytes = V1.asymUnwrap(owner.identity.kemX, owner.identity.mlkemSeed, ownerPkX, ownerPkM, wrapped, "scope-grant")

        val temp = V1.generateIdentity()
        val tempWrapped = V1.asymWrap(temp.pubKemX, temp.pubKemM, scopePrivateBytes, "scope-grant", V1.randomBytes(12))

        val json = """{"role":"$role",""" +
            """"tempPublicKey":{"suiteId":"v1","kemPublicKey":"${enc.encodeToString(temp.pubKemX + temp.pubKemM)}"},""" +
            """"tempWrappedScopePrivateKey":{"suiteId":"v1","bytes":"${enc.encodeToString(tempWrapped)}"}}"""
        val req = HttpRequest.newBuilder(URI.create("$apiBase/api/tenants/$tenantId/member-invites"))
            .header("Authorization", "Bearer ${owner.sessionToken}")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 200) throw RuntimeException("member-invite failed: ${resp.statusCode()} ${resp.body()}")
        val body = JSONObject(resp.body())
        return MemberInvite(
            inviteId = body.getString("inviteId"),
            bootstrapSecret = body.getString("bootstrapSecret"),
            tempPrivateKey = enc.encodeToString(V1.serializeIdentityPrivate(temp)),
        )
    }

    /**
     * Register a NEW account from a temp-wrap member invite: unwrap the scope with the temp private key
     * (from the URL fragment), re-wrap it to the new identity, register onto the existing scope.
     */
    fun registerViaMemberInvite(
        apiBase: String, inviteId: String, bootstrapSecret: String, tempPrivateKeyB64: String,
        email: String, displayName: String, password: String,
    ): RegisterViaInvitation.RegisterResult {
        val meta = RegisterViaInvitation.getInviteMetadata(apiBase, inviteId)
        val tempKem = dec.decode(meta.getJSONObject("tempPublicKey").getString("kemPublicKey"))
        val tempWrapped = dec.decode(meta.getJSONObject("tempWrappedScopePrivateKey").getString("bytes"))
        val scopePub = meta.getJSONObject("scopePublicKey").getString("kemPublicKey")
        val tempPriv = V1.deserializeIdentityPrivate(dec.decode(tempPrivateKeyB64))
        val scopePrivateBytes = V1.asymUnwrap(
            tempPriv.kemX, tempPriv.mlkemSeed, tempKem.copyOfRange(0, 32), tempKem.copyOfRange(32, tempKem.size), tempWrapped, "scope-grant",
        )
        return RegisterViaInvitation.registerWithScope(apiBase, inviteId, bootstrapSecret, email, displayName, password) { idX, idM ->
            scopePub to enc.encodeToString(V1.asymWrap(idX, idM, scopePrivateBytes, "scope-grant", V1.randomBytes(12)))
        }
    }
}
