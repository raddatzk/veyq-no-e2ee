package com.lunva.api.library.media

import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.jboss.resteasy.reactive.RestForm
import org.jboss.resteasy.reactive.multipart.FileUpload

@ApplicationScoped
@Path("/api/{tenantName}/media")
class LegacyMediaUploadResource @Inject constructor(
    private val mediaService: MediaService,
) {
    @POST
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    fun upload(
        @RestForm("file") file: FileUpload?,
        @RestForm("albumPath") albumPath: String?,
        @RestForm("title") title: String?,
    ): Response =
        Response.ok(mediaService.uploadMedia(file, albumPath, title)).build()
}
