package com.veyq.api.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.veyq.api.generated.model.UpdateMemoryShareRequest;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.Provider;

import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

@Provider
@ApplicationScoped
@Consumes(MediaType.APPLICATION_JSON)
public class UpdateMemoryShareRequestReader implements MessageBodyReader<UpdateMemoryShareRequest> {
    public static final String PRESENT_FIELDS_KEY = "veyq.updateMemoryShare.presentFields";

    @Inject ObjectMapper objectMapper;
    @Inject RoutingContext routingContext;

    @Override
    public boolean isReadable(
            Class<?> type,
            Type genericType,
            Annotation[] annotations,
            MediaType mediaType
    ) {
        return type == UpdateMemoryShareRequest.class;
    }

    @Override
    public UpdateMemoryShareRequest readFrom(
            Class<UpdateMemoryShareRequest> type,
            Type genericType,
            Annotation[] annotations,
            MediaType mediaType,
            MultivaluedMap<String, String> httpHeaders,
            InputStream entityStream
    ) throws IOException, WebApplicationException {
        String rawBody = new String(entityStream.readAllBytes(), StandardCharsets.UTF_8);
        JsonNode json = objectMapper.readTree(rawBody);
        Set<String> presentFields = new HashSet<>();
        json.fieldNames().forEachRemaining(presentFields::add);
        routingContext.put(PRESENT_FIELDS_KEY, presentFields);
        return objectMapper.treeToValue(json, UpdateMemoryShareRequest.class);
    }
}
