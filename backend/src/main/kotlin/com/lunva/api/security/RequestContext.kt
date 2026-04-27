package com.lunva.api.security

import com.lunva.api.domain.Tenant
import com.lunva.api.domain.User
import jakarta.enterprise.context.RequestScoped

@RequestScoped
class RequestContext {
    var user: User? = null
    var tenant: Tenant? = null
    var authentication: AuthenticationRequest? = null
}