package com.veyq.app.features.admin.serverusers

import com.veyq.app.shared.identity.ServerRole
import com.veyq.app.shared.identity.User
import com.veyq.app.shared.identity.UserRepository
import com.veyq.app.shared.security.SessionService
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.`is`
import org.hamcrest.Matchers.hasItem
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test

@QuarkusTest
class ServerAdminSliceTest {

    @Inject lateinit var users: UserRepository
    @Inject lateinit var sessions: SessionService

    private lateinit var ownerToken: String
    private lateinit var memberToken: String

    @BeforeTest
    fun seed() {
        QuarkusTransaction.requiringNew().run {
            val now = Instant.now()
            if (users.findByEmail("sa-owner@example.com") == null)
                users.persist(User().apply { id = "sa-owner"; email = "sa-owner@example.com"; displayName = "SA Owner"; serverRole = ServerRole.ServerOwner; createdAt = now })
            if (users.findByEmail("sa-member@example.com") == null)
                users.persist(User().apply { id = "sa-member"; email = "sa-member@example.com"; displayName = "SA Member"; serverRole = ServerRole.ServerMember; createdAt = now })
        }
        ownerToken = "Bearer ${sessions.issue("sa-owner")}"
        memberToken = "Bearer ${sessions.issue("sa-member")}"
    }

    @Test
    fun ownerListsRoster() {
        given().header("Authorization", ownerToken).get("/api/admin/users").then().statusCode(200)
            .body("users.email", hasItem("sa-member@example.com"))
    }

    @Test
    fun nonOwnerForbiddenToList() {
        given().header("Authorization", memberToken).get("/api/admin/users").then().statusCode(403)
    }

    @Test
    fun nonOwnerForbiddenToSetRole() {
        given().header("Authorization", memberToken).contentType("application/json")
            .body("""{"serverRole":"ServerOwner"}""")
            .put("/api/admin/users/sa-owner/server-role").then().statusCode(403)
    }

    @Test
    fun ownerPromotesAndDemotesMember() {
        // promote
        given().header("Authorization", ownerToken).contentType("application/json")
            .body("""{"serverRole":"ServerOwner"}""")
            .put("/api/admin/users/sa-member/server-role").then().statusCode(200)
            .body("serverRole", `is`("ServerOwner"))
        // demote back (>1 owner now, so allowed) to keep the roster tidy for other tests
        given().header("Authorization", ownerToken).contentType("application/json")
            .body("""{"serverRole":"ServerMember"}""")
            .put("/api/admin/users/sa-member/server-role").then().statusCode(200)
            .body("serverRole", `is`("ServerMember"))
    }

    @Test
    fun setRoleUnknownUserIs404() {
        given().header("Authorization", ownerToken).contentType("application/json")
            .body("""{"serverRole":"ServerMember"}""")
            .put("/api/admin/users/does-not-exist/server-role").then().statusCode(404)
    }
}
