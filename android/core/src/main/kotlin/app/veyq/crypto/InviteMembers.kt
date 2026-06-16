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
        if (resp.statusCode() != 200) throw RuntimeException("list members failed: ${resp.statusCode()}")
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
        // Forward secrecy: if the scope has content, mint a FRESH content key (not a re-wrap) and re-seal
        // every item under it. A removed member who cached the OLD content key cannot read content written
        // after the rotation, and the old ciphertext is overwritten — so the cached key opens nothing.
        val myGrant = Tenant.getMyScopeGrant(apiBase, owner.sessionToken, tenantId)
        val ckReq = HttpRequest.newBuilder(URI.create("$apiBase/api/scopes/${myGrant.getString("scopeId")}/content-key"))
            .header("Authorization", "Bearer ${owner.sessionToken}").GET().build()
        val ckResp = client.send(ckReq, HttpResponse.BodyHandlers.ofString())
        var ckPart = ""
        if (ckResp.statusCode() == 200) {
            val oldScopePriv = Tenant.openScopeGrant(myGrant, owner.identity, owner.kemPublicKey)
            val oldKem = dec.decode(myGrant.getJSONObject("scopePublicKey").getString("kemPublicKey"))
            val wrappedCk = dec.decode(JSONObject(ckResp.body()).getJSONObject("wrappedContentKey").getString("bytes"))
            val oldCk = V1.asymUnwrap(oldScopePriv.kemX, oldScopePriv.mlkemSeed, oldKem.copyOfRange(0, 32), oldKem.copyOfRange(32, oldKem.size), wrappedCk, "content-key")
            val newCk = V1.randomBytes(32)
            val rewrapped = V1.asymWrap(newScope.pubKemX, newScope.pubKemM, newCk, "content-key", V1.randomBytes(12))

            val itemsReq = HttpRequest.newBuilder(URI.create("$apiBase/api/scopes/${myGrant.getString("scopeId")}/items"))
                .header("Authorization", "Bearer ${owner.sessionToken}").GET().build()
            val itemsResp = client.send(itemsReq, HttpResponse.BodyHandlers.ofString())
            if (itemsResp.statusCode() != 200) throw RuntimeException("item list failed: ${itemsResp.statusCode()}")
            val itemsArr = JSONObject(itemsResp.body()).getJSONArray("items")
            val reSealedParts = mutableListOf<String>()
            for (i in 0 until itemsArr.length()) {
                val it = itemsArr.getJSONObject(i)
                val sealed = dec.decode(it.getJSONObject("sealedContent").getString("bytes"))
                val pt = V1.aeadOpen(oldCk, sealed.copyOfRange(0, 12), sealed.copyOfRange(12, sealed.size), ByteArray(0))
                val nonce = V1.randomBytes(12)
                val resealed = nonce + V1.aeadSeal(newCk, nonce, pt, ByteArray(0))
                reSealedParts.add("""{"id":"${it.getString("id")}","sealedContent":{"suiteId":"v1","bytes":"${enc.encodeToString(resealed)}"}}""")
            }

            // Memory-tree nodes are sealed under the content key too — re-seal each + recompute its slug token
            val nodesArr = MemoryTree.listNodes(apiBase, owner, myGrant.getString("scopeId"))
            val reSealedNodeParts = mutableListOf<String>()
            for (i in 0 until nodesArr.length()) {
                val n = nodesArr.getJSONObject(i)
                val content = MemoryTree.decodeNode(oldCk, n)
                val resealed = MemoryTree.resealNode(newCk, content)
                val parentSlot = if (n.isNull("parentId")) myGrant.getString("scopeId") else n.getString("parentId")
                val token = MemoryTree.slugTokenFor(newCk, parentSlot, content.getString("slug"))
                reSealedNodeParts.add("""{"id":"${n.getString("id")}","slugToken":"$token","sealedContent":{"suiteId":"v1","bytes":"${enc.encodeToString(resealed)}"}}""")
            }
            ckPart = ""","wrappedContentKey":{"suiteId":"v1","bytes":"${enc.encodeToString(rewrapped)}"},""" +
                """"reSealedItems":[${reSealedParts.joinToString(",")}],""" +
                """"reSealedNodes":[${reSealedNodeParts.joinToString(",")}]"""
        } else if (ckResp.statusCode() != 404) {
            throw RuntimeException("content-key fetch failed: ${ckResp.statusCode()}")
        }

        val json = """{"scopePublicKey":{"suiteId":"v1","kemPublicKey":"${enc.encodeToString(newScope.pubKemX + newScope.pubKemM)}"},""" +
            """"grants":[${grantParts.joinToString(",")}]$ckPart}"""
        val req = HttpRequest.newBuilder(URI.create("$apiBase/api/tenants/$tenantId/scope/rotate"))
            .header("Authorization", "Bearer ${owner.sessionToken}")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 200) throw RuntimeException("rotate scope failed: ${resp.statusCode()}")
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
        if (resp.statusCode() != 200) throw RuntimeException("change role failed: ${resp.statusCode()}")
        return JSONObject(resp.body())
    }

    /** Remove a member (instant layer of AD-24): revoke the member's scope grant + sessions. No crypto. */
    fun removeMember(apiBase: String, ownerToken: String, tenantId: String, userId: String): JSONObject {
        val req = HttpRequest.newBuilder(URI.create("$apiBase/api/tenants/$tenantId/members/$userId"))
            .header("Authorization", "Bearer $ownerToken").DELETE().build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 200) throw RuntimeException("remove member failed: ${resp.statusCode()}")
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
        if (resp.statusCode() != 200) throw RuntimeException("member-invite failed: ${resp.statusCode()}")
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
        val scopePub = meta.getJSONObject("scopePublicKey").getString("kemPublicKey")
        // the temp-wrapped grant is gated behind the bootstrap secret (not in public metadata)
        val material = RegisterViaInvitation.getInviteMaterial(apiBase, inviteId, bootstrapSecret)
        val tempKem = dec.decode(material.getJSONObject("tempPublicKey").getString("kemPublicKey"))
        val tempWrapped = dec.decode(material.getJSONObject("tempWrappedScopePrivateKey").getString("bytes"))
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
        // the temp-wrapped grant is gated behind the bootstrap secret (not in public metadata)
        val material = RegisterViaInvitation.getInviteMaterial(apiBase, inviteId, bootstrapSecret)
        val tempKem = dec.decode(material.getJSONObject("tempPublicKey").getString("kemPublicKey"))
        val tempWrapped = dec.decode(material.getJSONObject("tempWrappedScopePrivateKey").getString("bytes"))
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
        if (resp.statusCode() != 200) throw RuntimeException("accept failed: ${resp.statusCode()}")
        return JSONObject(resp.body())
    }

    // A passphrase that protects an invite's temp key is folded into a KEK via Argon2id (matching the web
    // ARGON params so a link minted on one platform opens on another). The temp key is sym-wrapped under it;
    // the link carries only the ciphertext + salt, so the email relay can't resolve it without the passphrase.
    private val inviteArgon = V1.ArgonParams(t = 3, m = 65536, p = 1, dkLen = 32)
    private const val TEMP_KEY_CONTEXT = "invite-temp-key"
    private fun passphraseKek(passphrase: String, salt: ByteArray): ByteArray =
        V1.kekRecovery(java.text.Normalizer.normalize(passphrase, java.text.Normalizer.Form.NFC).toByteArray(Charsets.UTF_8), salt, inviteArgon)

    /** Encrypt a temp private key (b64) under a passphrase → the `ek`/`ps` link fields (operator-blind invite). */
    fun protectTempKey(tempPrivateKeyB64: String, passphrase: String): Pair<String, String> {
        val salt = V1.randomBytes(16)
        val enc2 = V1.symWrap(passphraseKek(passphrase, salt), dec.decode(tempPrivateKeyB64), TEMP_KEY_CONTEXT, V1.randomBytes(12))
        return enc.encodeToString(enc2) to enc.encodeToString(salt) // (encTempKey, pwSalt)
    }

    /** Decrypt a passphrase-protected temp key back to its b64 form (wrong passphrase → throws on AEAD failure). */
    fun unprotectTempKey(encTempKey: String, pwSalt: String, passphrase: String): String =
        enc.encodeToString(V1.symUnwrap(passphraseKek(passphrase, dec.decode(pwSalt)), dec.decode(encTempKey), TEMP_KEY_CONTEXT))

    /**
     * Have the server email an already-assembled invite link to an address. The link is built client-side,
     * so the mode decides what the server/mail relay sees (pragmatic = temp key in the clear; passphrase =
     * only the encrypted temp key). The server validates ownership + that the link targets this instance.
     */
    fun emailMemberInvite(apiBase: String, token: String, tenantId: String, email: String, inviteId: String, link: String) {
        val json = JSONObject().put("email", email.trim()).put("inviteId", inviteId).put("link", link).toString()
        val req = HttpRequest.newBuilder(URI.create("$apiBase/api/tenants/$tenantId/member-invites/email"))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 204) throw RuntimeException("email invite failed: ${resp.statusCode()}")
    }
}
