package com.veyq.api.auth.filterchain

import com.veyq.api.auth.NotFoundException
import com.veyq.api.auth.UnauthorizedException
import com.veyq.api.domain.Tenant
import com.veyq.api.domain.User
import jakarta.enterprise.context.RequestScoped

@RequestScoped
class RequestContext {
    var user: User? = null
    var tenant: Tenant? = null
    var authentication: AuthenticationRequest? = null
    var clientIp: String = "unknown"

    fun requireUser(): User =
        user ?: throw UnauthorizedException("Authentication required")

    fun requireTenant(): Tenant =
        tenant ?: throw NotFoundException("Tenant not found")
}
