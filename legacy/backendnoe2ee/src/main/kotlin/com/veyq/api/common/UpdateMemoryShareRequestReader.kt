package com.veyq.api.common

import com.fasterxml.jackson.databind.ObjectMapper
import com.veyq.api.generated.model.UpdateMemoryShareRequest
import io.vertx.ext.web.RoutingContext
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.MultivaluedMap
import jakarta.ws.rs.ext.MessageBodyReader
import jakarta.ws.rs.ext.Provider
import java.io.IOException
import java.io.InputStream
import java.lang.reflect.Type
import java.nio.charset.StandardCharsets

@Provider
@ApplicationScoped
@Consumes(MediaType.APPLICATION_JSON)
class UpdateMemoryShareRequestReader @Inject constructor(
    private val objectMapper: ObjectMapper,
    private val routingContext: RoutingContext,
) : MessageBodyReader<UpdateMemoryShareRequest> {
    override fun isReadable(
        type: Class<*>,
        genericType: Type,
        annotations: Array<out Annotation>,
        mediaType: MediaType,
    ): Boolean =
        type == UpdateMemoryShareRequest::class.java

    @Throws(IOException::class, WebApplicationException::class)
    override fun readFrom(
        type: Class<UpdateMemoryShareRequest>,
        genericType: Type,
        annotations: Array<out Annotation>,
        mediaType: MediaType,
        httpHeaders: MultivaluedMap<String, String>,
        entityStream: InputStream,
    ): UpdateMemoryShareRequest {
        val rawBody = String(entityStream.readAllBytes(), StandardCharsets.UTF_8)
        val json = objectMapper.readTree(rawBody)
        val presentFields = json.fieldNames().asSequence().toSet()
        routingContext.put(PRESENT_FIELDS_KEY, presentFields)
        return objectMapper.treeToValue(json, UpdateMemoryShareRequest::class.java)
    }

    companion object {
        const val PRESENT_FIELDS_KEY = "veyq.updateMemoryShare.presentFields"
    }
}
