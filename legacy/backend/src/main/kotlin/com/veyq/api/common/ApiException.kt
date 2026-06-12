package com.veyq.api.common

import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response

open class ApiException(
    val code: String,
    message: String,
    status: Response.Status,
) : WebApplicationException(message, status)
