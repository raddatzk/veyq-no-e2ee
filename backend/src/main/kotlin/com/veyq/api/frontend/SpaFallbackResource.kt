package com.veyq.api.frontend

import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.GET
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import java.nio.charset.StandardCharsets

@Path("/")
@ApplicationScoped
class SpaFallbackResource {

    @GET
    @Produces(MediaType.TEXT_HTML)
    fun index(): String = indexHtml()

    @GET
    @Path("/{path:.*}")
    @Produces(MediaType.TEXT_HTML)
    fun route(@PathParam("path") path: String): String {
        if (isBackendOrAssetPath(path)) {
            throw NotFoundException()
        }
        return indexHtml()
    }

    private fun isBackendOrAssetPath(path: String): Boolean =
        path.startsWith("api/") ||
            path.startsWith("q/") ||
            path.startsWith("assets/") ||
            path.contains(".")

    private fun indexHtml(): String {
        val stream = Thread.currentThread().contextClassLoader
            .getResourceAsStream("META-INF/resources/index.html")
            ?: throw NotFoundException()
        return stream.use { String(it.readAllBytes(), StandardCharsets.UTF_8) }
    }
}
