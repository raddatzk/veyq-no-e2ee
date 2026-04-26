package com.lunva.api.auth

import com.lunva.api.common.ApiException
import jakarta.ws.rs.core.Response

class UnauthorizedException(message: String) : ApiException(message, Response.Status.UNAUTHORIZED)
class ForbiddenException(message: String) : ApiException(message, Response.Status.FORBIDDEN)
