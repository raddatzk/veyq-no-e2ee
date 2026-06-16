package app.veyq.crypto

import org.json.JSONObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64

/**
 * uc.admin.create-tenant-invite-owner (Android) — provision a tenant shell + owner invite, and claim
 * ownership by minting the tenant scope keypair and granting it to oneself. The server stays blind.
 */
object Tenant {
    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val dec = Base64.getUrlDecoder()
    private val client = HttpClient.newHttpClient()

    private fun send(apiBase: String, path: String, token: String, method: String, body: String? = null): String {
        val b = HttpRequest.newBuilder(URI.create("$apiBase$path")).header("Authorization", "Bearer $token")
        if (method == "POST") {
            b.header("Content-Type", "application/json")
            b.POST(if (body != null) HttpRequest.BodyPublishers.ofString(body) else HttpRequest.BodyPublishers.noBody())
        } else {
            b.GET()
        }
        val resp = client.send(b.build(), HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 200) throw RuntimeException("$method $path failed: ${resp.statusCode()}")
        return resp.body()
    }

    fun createTenant(apiBase: String, token: String, name: String, displayName: String, domain: String?): JSONObject {
        // A tenant is domain-scoped (domain required + unique). The UI passes a real domain; tests/owner-claim
        // omit it → derive a unique one from the (unique) handle so the request still validates.
        val dom = domain?.takeIf { it.isNotEmpty() } ?: "$name.local"
        return JSONObject(send(apiBase, "/api/tenants", token, "POST", """{"name":${JSONObject.quote(name)},"displayName":${JSONObject.quote(displayName)},"domain":${JSONObject.quote(dom)}}"""))
    }

    fun issueOwnerInvite(apiBase: String, token: String, tenantId: String): JSONObject =
        JSONObject(send(apiBase, "/api/tenants/$tenantId/owner-invite", token, "POST"))

    fun getMyScopeGrant(apiBase: String, token: String, tenantId: String): JSONObject =
        JSONObject(send(apiBase, "/api/tenants/$tenantId/scope", token, "GET"))

    fun claimTenantOwnership(
        apiBase: String, token: String, inviteId: String, bootstrapSecret: String, identityKemPublicKey: ByteArray,
    ): JSONObject {
        val pkX = identityKemPublicKey.copyOfRange(0, 32)
        val pkM = identityKemPublicKey.copyOfRange(32, identityKemPublicKey.size)
        val scope = V1.generateIdentity() // reuse the hybrid-KEM bundle for the scope (sign field unused)
        val scopePublic = scope.pubKemX + scope.pubKemM
        val scopePrivate = V1.serializeIdentityPrivate(scope)
        val grant = V1.asymWrap(pkX, pkM, scopePrivate, "scope-grant", V1.randomBytes(12))
        val json = """{"bootstrapSecret":"$bootstrapSecret",""" +
            """"scopePublicKey":{"suiteId":"v1","kemPublicKey":"${enc.encodeToString(scopePublic)}"},""" +
            """"wrappedScopePrivateKey":{"suiteId":"v1","bytes":"${enc.encodeToString(grant)}"}}"""
        return JSONObject(send(apiBase, "/api/tenant-invites/$inviteId/claim", token, "POST", json))
    }

    /** Unwrap a scope grant with the holder's identity key → the scope private bundle. */
    fun openScopeGrant(grant: JSONObject, identity: V1.IdentityPrivate, identityKemPublicKey: ByteArray): V1.IdentityPrivate {
        val pkX = identityKemPublicKey.copyOfRange(0, 32)
        val pkM = identityKemPublicKey.copyOfRange(32, identityKemPublicKey.size)
        val wrapped = dec.decode(grant.getJSONObject("wrappedScopePrivateKey").getString("bytes"))
        val scopePrivBytes = V1.asymUnwrap(identity.kemX, identity.mlkemSeed, pkX, pkM, wrapped, "scope-grant")
        return V1.deserializeIdentityPrivate(scopePrivBytes)
    }
}
