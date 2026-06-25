package com.veyq.app.features.admin.setup

import com.fasterxml.jackson.databind.ObjectMapper
import com.veyq.app.generated.model.CreateServerOwnerRequest
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Verifies the spec-declared trim sanitization actually binds: the Jackson deserializer trims the
 *  name fields before validation/storage. */
@QuarkusTest
class SetupRequestSanitizationTest {

    @Inject
    lateinit var mapper: ObjectMapper

    @Test
    fun trimsNameFields() {
        val json = """
            {"email":"  kev@example.com  ","displayName":"  Kevin  ","instanceName":"\tOur Family veyq ","suiteId":"v1",
             "identityPublicKey":"YQ==","wrappedIdentityPrivateKey":"YQ==","authPublicKey":"YQ==",
             "masterKeyWraps":[{"method":"PASSWORD","wrappedKey":"YQ=="}]}
        """.trimIndent()

        val req = mapper.readValue(json, CreateServerOwnerRequest::class.java)

        assertEquals("kev@example.com", req.email)
        assertEquals("Kevin", req.displayName)
        assertEquals("Our Family veyq", req.instanceName)
    }
}
