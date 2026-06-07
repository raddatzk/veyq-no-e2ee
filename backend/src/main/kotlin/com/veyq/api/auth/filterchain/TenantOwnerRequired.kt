package com.veyq.api.auth.filterchain

import com.veyq.api.auth.ForbiddenException
import com.veyq.api.domain.TenantMemberStatus
import com.veyq.api.domain.TenantRole
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

        val user = requestContext.requireUser()
        val tenant = requestContext.requireTenant()

        val membership = tenant.members.firstOrNull { it.user.id == user.id }
            ?: throw ForbiddenException("User ${user.displayName} is not member in tenant ")

        if (membership.status != TenantMemberStatus.Active || membership.role != TenantRole.TenantOwner) {
            throw ForbiddenException("User ${user.displayName} has insufficient role in tenant ${tenant.name}")
        }

        return context.proceed()
    }
}
