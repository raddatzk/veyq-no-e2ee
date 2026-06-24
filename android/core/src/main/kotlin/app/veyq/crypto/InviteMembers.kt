package app.veyq.crypto

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/**
 * uc.admin.invite-members-roles (Android) — member management for a tenant: temp-wrap invites for new
 * people (email / white-label link), listing + rotating the scope, removing members and changing roles.
 * Adding people is invite-only — there is no directory lookup of arbitrary emails. The server stays blind.
 */
object InviteMembers {
    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val dec = Base64.getUrlDecoder()

    private fun blob(bytes: ByteArray): JSONObject = JSONObject().put("suiteId", "v1").put("bytes", enc.encodeToString(bytes))

    /** List a tenant's members with their published identity public keys (for re-wrapping a rotated scope). */
    fun listTenantMembers(apiBase: String, token: String, tenantId: String): JSONArray {
        return JSONObject(ApiClient.ok(apiBase, token, "/api/tenant/members", "GET", label = "list members")).getJSONArray("members")
    }

    /**
     * Rotate the tenant scope (eventual layer of AD-24): mint a NEW scope keypair and re-wrap it to every
     * active member, so a previously-removed member loses key access to future content.
     */
    fun rotateScope(apiBase: String, owner: Login.UnlockedSession, tenantId: String): JSONObject {
        val members = listTenantMembers(apiBase, owner.sessionToken, tenantId)
        val newScope = V1.generateIdentity()
        val newScopePrivate = V1.serializeIdentityPrivate(newScope)
        val grants = JSONArray()
        for (i in 0 until members.length()) {
            val m = members.getJSONObject(i)
            if (m.getString("status") != "ACTIVE") continue
            val kem = dec.decode(m.getJSONObject("identityPublicKey").getString("kemPublicKey"))
            val wrapped = V1.asymWrap(kem.copyOfRange(0, 32), kem.copyOfRange(32, kem.size), newScopePrivate, "scope-grant", V1.randomBytes(12))
            grants.put(JSONObject().put("userId", m.getString("userId")).put("wrappedScopePrivateKey", blob(wrapped)))
        }
        val body = JSONObject()
            .put("scopePublicKey", JSONObject().put("suiteId", "v1").put("kemPublicKey", enc.encodeToString(newScope.pubKemX + newScope.pubKemM)))
            .put("grants", grants)
        // Forward secrecy: if the scope has content, mint a FRESH content key (not a re-wrap) and re-seal
        // every item under it. A removed member who cached the OLD content key cannot read content written
        // after the rotation, and the old ciphertext is overwritten — so the cached key opens nothing.
        val myGrant = Tenant.getMyScopeGrant(apiBase, owner.sessionToken, tenantId)
        val ckResp = ApiClient.send(apiBase, owner.sessionToken, "/api/scopes/${myGrant.getString("scopeId")}/content-key", "GET")
        if (ckResp.statusCode() == 200) {
            val oldScopePriv = Tenant.openScopeGrant(myGrant, owner.identity, owner.kemPublicKey)
            val oldKem = dec.decode(myGrant.getJSONObject("scopePublicKey").getString("kemPublicKey"))
            val wrappedCk = dec.decode(JSONObject(ckResp.body()).getJSONObject("wrappedContentKey").getString("bytes"))
            val oldCk = V1.asymUnwrap(oldScopePriv.kemX, oldScopePriv.mlkemSeed, oldKem.copyOfRange(0, 32), oldKem.copyOfRange(32, oldKem.size), wrappedCk, "content-key")
            val newCk = V1.randomBytes(32)
            val rewrapped = V1.asymWrap(newScope.pubKemX, newScope.pubKemM, newCk, "content-key", V1.randomBytes(12))

            val itemsArr = JSONObject(ApiClient.ok(apiBase, owner.sessionToken, "/api/scopes/${myGrant.getString("scopeId")}/items", "GET", label = "item list")).getJSONArray("items")
            val reSealedItems = JSONArray()
            for (i in 0 until itemsArr.length()) {
                val it = itemsArr.getJSONObject(i)
                val sealed = dec.decode(it.getJSONObject("sealedContent").getString("bytes"))
                val pt = V1.aeadOpen(oldCk, sealed.copyOfRange(0, 12), sealed.copyOfRange(12, sealed.size), ByteArray(0))
                val nonce = V1.randomBytes(12)
                val resealed = nonce + V1.aeadSeal(newCk, nonce, pt, ByteArray(0))
                reSealedItems.put(JSONObject().put("id", it.getString("id")).put("sealedContent", blob(resealed)))
            }

            // Memory-tree nodes are sealed under the content key too — re-seal each + recompute its slug token
            val nodesArr = MemoryTree.listNodes(apiBase, owner, myGrant.getString("scopeId"))
            val reSealedNodes = JSONArray()
            for (i in 0 until nodesArr.length()) {
                val n = nodesArr.getJSONObject(i)
                val content = MemoryTree.decodeNode(oldCk, n)
                val resealed = MemoryTree.resealNode(newCk, content)
                val parentSlot = if (n.isNull("parentId")) myGrant.getString("scopeId") else n.getString("parentId")
                val token = MemoryTree.slugTokenFor(newCk, parentSlot, content.getString("slug"))
                reSealedNodes.put(JSONObject().put("id", n.getString("id")).put("slugToken", token).put("sealedContent", blob(resealed)))
            }
            body.put("wrappedContentKey", blob(rewrapped)).put("reSealedItems", reSealedItems).put("reSealedNodes", reSealedNodes)
        } else if (ckResp.statusCode() != 404) {
            throw RuntimeException("content-key fetch failed: ${ckResp.statusCode()}")
        }

        return JSONObject(ApiClient.ok(apiBase, owner.sessionToken, "/api/tenant/scope/rotate", "POST", body.toString(), label = "rotate scope"))
    }

    /** Change a member's role (membership/settings authority only — not content access, ADR 0005). No crypto. */
    fun changeMemberRole(apiBase: String, ownerToken: String, tenantId: String, userId: String, role: String): JSONObject {
        val json = JSONObject().put("role", role).toString()
        return JSONObject(ApiClient.ok(apiBase, ownerToken, "/api/tenant/members/$userId/role", "PUT", json, label = "change role"))
    }

    /** Remove a member (instant layer of AD-24): revoke the member's scope grant + sessions. No crypto. */
    fun removeMember(apiBase: String, ownerToken: String, tenantId: String, userId: String): JSONObject =
        JSONObject(ApiClient.ok(apiBase, ownerToken, "/api/tenant/members/$userId", "DELETE", label = "remove member"))

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

        val json = JSONObject()
            .put("role", role)
            .put("tempPublicKey", JSONObject().put("suiteId", "v1").put("kemPublicKey", enc.encodeToString(temp.pubKemX + temp.pubKemM)))
            .put("tempWrappedScopePrivateKey", blob(tempWrapped))
            .toString()
        val body = JSONObject(ApiClient.ok(apiBase, owner.sessionToken, "/api/tenant/member-invites", "POST", json, label = "member-invite"))
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
        val json = JSONObject().put("bootstrapSecret", bootstrapSecret).put("wrappedScopePrivateKey", blob(rewrapped)).toString()
        return JSONObject(ApiClient.ok(apiBase, session.sessionToken, "/api/tenant-invites/$inviteId/accept", "POST", json, label = "accept"))
    }

    // A passphrase that protects an invite's temp key is folded into a KEK via Argon2id. The params are NOT
    // carried in the link, so they MUST equal the web ARGON params (t=3, m=32768, p=1) or a link minted on one
    // platform won't open on another. The temp key is sym-wrapped under it; the link carries only the
    // ciphertext + salt, so the email relay can't resolve it without the passphrase.
    private val inviteArgon = V1.ArgonParams(t = 3, m = 32768, p = 1, dkLen = 32)
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
        val resp = ApiClient.send(apiBase, token, "/api/tenant/member-invites/email", "POST", json)
        if (resp.statusCode() != 204) throw RuntimeException("email invite failed: ${resp.statusCode()}")
    }
}
