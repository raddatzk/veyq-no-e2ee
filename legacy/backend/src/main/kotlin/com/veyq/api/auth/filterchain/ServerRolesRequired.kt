package com.veyq.api.auth.filterchain

import com.veyq.api.auth.ForbiddenException
import com.veyq.api.domain.ServerRole
import jakarta.annotation.Priority
import jakarta.enterprise.util.Nonbinding
import jakarta.inject.Inject
import jakarta.interceptor.AroundInvoke
import jakarta.interceptor.Interceptor
import jakarta.interceptor.InterceptorBinding
import jakarta.interceptor.InvocationContext

@InterceptorBinding
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class ServerRolesRequired(
    @get:Nonbinding vararg val value: ServerRole
)

@ServerRolesRequired
@Interceptor
@Priority(Interceptor.Priority.APPLICATION)
class ServerRolesAllowedInterceptor @Inject constructor(
    private val requestContext: RequestContext,
) {
    @AroundInvoke
    fun authorize(context: InvocationContext): Any? {
        val annotation = findServerRolesRequiredAnnotation(context)

        val user = requestContext.requireUser()

        if (!annotation.value.toSet().contains(user.role)) {
            throw ForbiddenException("User ${user.displayName} has insufficient role on server")
        }

        return context.proceed()
    }

    private fun findServerRolesRequiredAnnotation(context: InvocationContext): ServerRolesRequired {
        return context.method.getAnnotation(ServerRolesRequired::class.java)
            ?: context.target::class.java.getAnnotation(ServerRolesRequired::class.java)
            ?: throw IllegalStateException("Missing ServerRolesAllowed annotation")
    }
}
