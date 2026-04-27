package com.lunva.api.setup

import com.lunva.api.common.ErrorResponse
import com.lunva.api.domain.UserRepo
import io.quarkus.runtime.StartupEvent
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.inject.Inject
import jakarta.ws.rs.Priorities
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerRequestFilter
import jakarta.ws.rs.ext.Provider
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

@Provider
@ApplicationScoped
@Priority(Priorities.USER)
class SetupFilter @Inject constructor(
    private val userRepo: UserRepo,
): ContainerRequestFilter {
    @Volatile
    private var setupCompleted: Boolean = false

    fun initialize(@Observes event: StartupEvent) {
        refreshSetupStatus()
    }

    override fun filter(requestContext: ContainerRequestContext) {
        val path = requestContext.uriInfo.requestUri.path.trimStart('/')
        if (!path.startsWith("api/") || isSetupAllowed(path)) {
            return
        }

        if (!isSetupCompleted()) {
            requestContext.abortWith(
                Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(ErrorResponse("Setup required"))
                    .build()
            )
        }
    }

    fun isSetupCompleted(): Boolean {
        return setupCompleted
    }

    fun markSetupCompleted() {
        setupCompleted = true
    }

    fun refreshSetupStatus(): Boolean {
        return synchronized(this) {
            (userRepo.count() > 0L).also {
                setupCompleted = it
            }
        }
    }

    private fun isSetupAllowed(path: String): Boolean {
        return path == "api/setup" || path == "api/setup/admin"
    }
}
