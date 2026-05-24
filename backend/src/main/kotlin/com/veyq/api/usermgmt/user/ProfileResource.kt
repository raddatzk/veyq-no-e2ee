package com.veyq.api.usermgmt.user

import com.veyq.api.generated.api.ProfilesApi
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.core.Response

@ApplicationScoped
class ProfileResource @Inject constructor(
    private val userService: UserService,
) : ProfilesApi {
    override fun getUserProfile(tenantName: String, userPermalink: String): Response =
        Response.ok(userService.getUserProfile(userPermalink)).build()
}
