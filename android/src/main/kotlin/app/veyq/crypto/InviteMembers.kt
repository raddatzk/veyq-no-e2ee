package app.veyq.crypto

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64

/**
 * uc.admin.invite-members-roles (Android) — member management for a tenant: temp-wrap invites for new
 * people (email / white-label link), listing + rotating the scope, removing members and changing roles.
 * Adding people is invite-only — there is no directory lookup of arbitrary emails. The server stays blind.
 */
object InviteMembers {
    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val dec = Base64.getUrlDecoder()
    private val client = HttpClient.newHttpClient()

    /** List a tenant's members with their published identity public keys (for re-wrapping a rotated scope). */
    fun listTenantMembers(apiBase: String, token: String, tenantId: String): JSONArray {
        val req = HttpRequest.newBuilder(URI.create("$apiBase/api/tenants/$tenantId/members"))
            .header("Authorization", "Bearer $token").GET().build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 200) throw RuntimeException("list members failed: ${resp.statusCode()} ${resp.body()}")
        return JSONObject(resp.body()).getJSONArray("members")
    }

    /**
     * Rotate the tenant scope (eventual layer of AD-24): mint a NEW scope keypair and re-wrap it to every
     * active member, so a previously-removed member loses key access to future content.
     */
    fun rotateScope(apiBase: String, owner: Login.UnlockedSession, tenantId: String): JSONObject {
        val members = listTenantMembers(apiBase, owner.sessionToken, tenantId)
        val newScope = V1.generateIdentity()
        val newScopePrivate = V1.serializeIdentityPrivate(newScope)
        val grantParts = mutableListOf<String>()
        for (i in 0 until members.length()) {
            val m = members.getJSONObject(i)
            if (m.getString("status") != "ACTIVE") continue
            val kem = dec.decode(m.getJSONObject("identityPublicKey").getString("kemPublicKey"))
            val wrapped = V1.asymWrap(kem.copyOfRange(0, 32), kem.copyOfRange(32, kem.size), newScopePrivate, "scope-grant", V1.randomBytes(12))
            grantParts.add("""{"userId":"${m.getString("userId")}","wrappedScopePrivateKey":{"suiteId":"v1","bytes":"${enc.encodeToString(wrapped)}"}}""")
        }
        val json = """{"scopePublicKey":{"suiteId":"v1","kemPublicKey":"${enc.encodeToString(newScope.pubKemX + newScope.pubKemM)}"},""" +
            """"grants":[${grantParts.joinToString(",")}]}"""
        val req = HttpRequest.newBuilder(URI.create("$apiBase/api/tenants/$tenantId/scope/rotate"))
            .header("Authorization", "Bearer ${owner.sessionToken}")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 200) throw RuntimeException("rotate scope failed: ${resp.statusCode()} ${resp.body()}")
        return JSONObject(resp.body())
    }

    /** Change a member's role (membership/settings authority only — not content access, ADR 0005). No crypto. */
    fun changeMemberRole(apiBase: String, ownerToken: String, tenantId: String, userId: String, role: String): JSONObject {
        val req = HttpRequest.newBuilder(URI.create("$apiBase/api/tenants/$tenantId/members/$userId/role"))
            .header("Authorization", "Bearer $ownerToken")
            .header("Content-Type", "application/json")
            .method("PUT", HttpRequest.BodyPublishers.ofString("""{"role":"$role"}"""))
            .build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 200) throw RuntimeException("change role failed: ${resp.statusCode()} ${resp.body()}")
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

    /**
     * Accept a temp-wrap member invite onto an EXISTING (already logged-in) account — the "confirm" path.
     * Unwrap with the temp key, re-wrap to the caller's own identity; the server adds grant + membership.
     */
    fun acceptMemberInvite(
        apiBase: String, session: Login.UnlockedSession, inviteId: String, bootstrapSecret: String, tempPrivateKeyB64: String,
    ): JSONObject {
        val meta = RegisterViaInvitation.getInviteMetadata(apiBase, inviteId)
        val tempKem = dec.decode(meta.getJSONObject("tempPublicKey").getString("kemPublicKey"))
        val tempWrapped = dec.decode(meta.getJSONObject("tempWrappedScopePrivateKey").getString("bytes"))
        val tempPriv = V1.deserializeIdentityPrivate(dec.decode(tempPrivateKeyB64))
        val scopePrivateBytes = V1.asymUnwrap(
            tempPriv.kemX, tempPriv.mlkemSeed, tempKem.copyOfRange(0, 32), tempKem.copyOfRange(32, tempKem.size), tempWrapped, "scope-grant",
        )
        val ownKem = session.kemPublicKey
        val rewrapped = V1.asymWrap(ownKem.copyOfRange(0, 32), ownKem.copyOfRange(32, ownKem.size), scopePrivateBytes, "scope-grant", V1.randomBytes(12))
        val json = """{"bootstrapSecret":"$bootstrapSecret","wrappedScopePrivateKey":{"suiteId":"v1","bytes":"${enc.encodeToString(rewrapped)}"}}"""
        val req = HttpRequest.newBuilder(URI.create("$apiBase/api/tenant-invites/$inviteId/accept"))
            .header("Authorization", "Bearer ${session.sessionToken}")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 200) throw RuntimeException("accept failed: ${resp.statusCode()} ${resp.body()}")
        return JSONObject(resp.body())
    }
}
