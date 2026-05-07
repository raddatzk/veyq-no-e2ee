package com.lunva.api.common

import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response

open class ApiException(message: String, status: Response.Status) : WebApplicationException(message, status)
