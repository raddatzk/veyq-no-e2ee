package com.lunva.api.auth

import com.lunva.api.common.ApiException
import jakarta.ws.rs.core.Response

class UnauthorizedException(message: String) : ApiException(message, Response.Status.UNAUTHORIZED)
class ForbiddenException(message: String) : ApiException(message, Response.Status.FORBIDDEN)
class BadRequestException(message: String) : ApiException(message, Response.Status.BAD_REQUEST)
class NotFoundException(message: String) : ApiException(message, Response.Status.NOT_FOUND)
class ConflictException(message: String) : ApiException(message, Response.Status.CONFLICT)
