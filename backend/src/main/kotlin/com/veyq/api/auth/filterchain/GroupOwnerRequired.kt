package com.veyq.api.auth.filterchain

import com.veyq.api.auth.ForbiddenException
import com.veyq.api.auth.NotFoundException
import com.veyq.api.domain.GroupRole
import jakarta.annotation.Priority
import jakarta.inject.Inject
import jakarta.interceptor.AroundInvoke
import jakarta.interceptor.Interceptor
import jakarta.interceptor.InterceptorBinding
import jakarta.interceptor.InvocationContext
import jakarta.ws.rs.PathParam

@InterceptorBinding
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class GroupOwnerRequired

@GroupOwnerRequired
@Interceptor
@Priority(Interceptor.Priority.APPLICATION)
class GroupRolesRequiredInterceptor @Inject constructor(
    private val requestContext: RequestContext,
) {
    @AroundInvoke
    fun authorize(context: InvocationContext): Any? {

        val groupName = findGroupName(context)

        val group = requestContext.requireTenant().groups.firstOrNull { it.name == groupName }
            ?: throw NotFoundException("Group $groupName not found")

        val user = requestContext.requireUser()

        val membership = group.members.firstOrNull { it.user.id == user.id }
            ?: throw ForbiddenException("User ${user.id} is not member in group $groupName")

        if (membership.role != GroupRole.GroupOwner) {
            throw ForbiddenException("User ${user.id} has insufficient role in group $groupName")
        }

        return context.proceed()
    }

    private fun findGroupName(context: InvocationContext): String {
        context.method.parameterAnnotations.forEachIndexed { index, annotations ->
            val pathParam = annotations.filterIsInstance<PathParam>()
                .firstOrNull { it.value == "groupName" }
            if (pathParam != null) {
                return context.parameters[index] as? String
                    ?: throw IllegalStateException("groupName path parameter must be a String")
            }
        }
        throw IllegalStateException("GroupRolesRequired methods must declare @PathParam(\"groupName\")")
    }
}
