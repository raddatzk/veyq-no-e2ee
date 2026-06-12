package com.veyq.app.platform.web

import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType

/**
 * Walking-skeleton liveness endpoint. Infrastructure, not part of the spec-first API contract —
 * hence the direct JAX-RS annotations (slice Resources implement generated `*Api` interfaces instead).
 */
@Path("/api/health")
class HealthResource {
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    fun health(): Map<String, String> = mapOf("name" to "veyq", "status" to "UP")
}
