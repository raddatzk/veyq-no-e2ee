package com.lunva.api.setup

import com.lunva.api.common.ErrorResponse
import com.lunva.api.domain.AppConfig
import com.lunva.api.domain.AppConfigRepo
import com.lunva.api.domain.UserRepo
import com.soundicly.jnanoidenhanced.jnanoid.NanoIdUtils
import io.quarkus.runtime.StartupEvent
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.inject.Inject
import jakarta.inject.Singleton
import jakarta.transaction.Transactional
import jakarta.ws.rs.Priorities
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerRequestFilter
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.Provider

@Singleton
class ServerState {
    var setupCompleted = false

    var serverId = ""

    fun markSetupCompleted() {
        setupCompleted = true
    }
}

@Provider
@ApplicationScoped
@Priority(Priorities.USER)
class SetupFilter @Inject constructor(
    private val userRepo: UserRepo,
    private val appConfigRepo: AppConfigRepo,
    private val serverState: ServerState,
) : ContainerRequestFilter {

    fun initialize(@Observes event: StartupEvent) {
        refreshSetupStatus()
    }

    override fun filter(requestContext: ContainerRequestContext) {
        val path = requestContext.uriInfo.requestUri.path.trimStart('/')
        if (!path.startsWith("api/") || isSetupAllowed(path)) {
            return
        }

        if (!serverState.setupCompleted) {
            requestContext.abortWith(
                Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(ErrorResponse("Setup required"))
                    .build()
            )
        }
    }

    @Transactional
    fun refreshSetupStatus() {
        synchronized(this) {
            (userRepo.count() > 0L).also {
                serverState.setupCompleted = it
            }

            val serverId = appConfigRepo.findByKey(AppConfig.SERVER_ID)
            if (serverId == null) {
                serverState.serverId = NanoIdUtils.randomNanoId(16)
                AppConfig().also {
                    it.configKey = AppConfig.SERVER_ID
                    it.configValue = serverState.serverId
                    it.persist()
                }
            } else {
                serverState.serverId = serverId.configValue
            }
        }
    }

    private fun isSetupAllowed(path: String): Boolean {
        return path == "api/setup" || path == "api/setup/admin"
    }
}
