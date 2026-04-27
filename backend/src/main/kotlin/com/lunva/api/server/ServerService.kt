package com.lunva.api.server

import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

@ApplicationScoped
class ServerService @Inject constructor(
    private val serverConfig: ServerConfig,
){

    fun server(): ServerSummary {
        return ServerSummary(serverConfig.name)
    }
}