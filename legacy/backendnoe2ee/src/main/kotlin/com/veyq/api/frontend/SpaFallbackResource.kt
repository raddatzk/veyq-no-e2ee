package com.veyq.api.frontend

import com.veyq.api.settings.VeyqConfig
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.GET
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.UriInfo
import java.net.URI
import java.nio.charset.StandardCharsets

@Path("/")
@ApplicationScoped
class SpaFallbackResource @Inject constructor(
    private val veyqConfig: VeyqConfig,
) {
    @Context
    lateinit var uriInfo: UriInfo

    @GET
    @Produces(MediaType.TEXT_HTML)
    fun index(): String = indexHtml(uriInfo.requestUri)

    @GET
    @Path("/{path:.*}")
    @Produces(MediaType.TEXT_HTML)
    fun route(@PathParam("path") path: String): String {
        if (isBackendOrAssetPath(path)) {
            throw NotFoundException()
        }
        return indexHtml(uriInfo.requestUri)
    }

    private fun isBackendOrAssetPath(path: String): Boolean =
        path.startsWith("api/") ||
            path.startsWith("q/") ||
            path.startsWith("assets/") ||
            path.contains(".")

    private fun indexHtml(requestUri: URI): String {
        val stream = Thread.currentThread().contextClassLoader
            .getResourceAsStream("META-INF/resources/index.html")
            ?: throw NotFoundException()
        val html = stream.use { String(it.readAllBytes(), StandardCharsets.UTF_8) }
        return injectSmartAppBanner(html, requestUri)
    }

    private fun injectSmartAppBanner(html: String, requestUri: URI): String {
        val appStoreId = veyqConfig.mobile.ios.appStoreId.orElse("").trim().takeIf { it.matches(Regex("\\d+")) }
            ?: return html
        val appArgument = canonicalAppArgument(requestUri)
        val meta = """    <meta name="apple-itunes-app" content="app-id=$appStoreId, app-argument=${htmlAttribute(appArgument)}" />"""
        return html.replaceFirst("  </head>", "$meta\n  </head>")
    }

    private fun canonicalAppArgument(requestUri: URI): String {
        val configuredBaseUrl = veyqConfig.publicWebBaseUrl.orElse("").trim()
        if (configuredBaseUrl.isBlank()) {
            return requestUri.toString()
        }

        val baseUri = URI.create(configuredBaseUrl.trimEnd('/') + "/")
        val relativePath = (requestUri.rawPath ?: "/").removePrefix("/")
        val relative = if (requestUri.rawQuery.isNullOrBlank()) {
            relativePath
        } else {
            "$relativePath?${requestUri.rawQuery}"
        }
        return baseUri.resolve(relative).toString()
    }

    private fun htmlAttribute(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
}
