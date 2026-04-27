package com.lunva.api.security

import com.lunva.api.auth.ForbiddenException
import com.lunva.api.domain.TenantRole
import jakarta.annotation.Priority
import jakarta.inject.Inject
import jakarta.interceptor.AroundInvoke
import jakarta.interceptor.Interceptor
import jakarta.interceptor.InterceptorBinding
import jakarta.interceptor.InvocationContext

@InterceptorBinding
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class TenantOwnerRequired

@TenantOwnerRequired
@Interceptor
@Priority(Interceptor.Priority.APPLICATION)
class TenantRolesAllowedInterceptor @Inject constructor(
    private val requestContext: RequestContext,
) {
    @AroundInvoke
    fun authorize(context: InvocationContext): Any? {

        val user = requestContext.user!!

        val membership = requestContext.tenant!!.members.firstOrNull { it.user == user }
            ?: throw ForbiddenException("User ${user.displayName} is not member in tenant ")

        if (membership.role != TenantRole.TENANT_OWNER) {
            throw ForbiddenException("User ${user.displayName} has insufficient role in tenant ${requestContext.tenant!!.name}")
        }

        return context.proceed()
    }
}

