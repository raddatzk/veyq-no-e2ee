package com.veyq.api.auth

import com.veyq.api.common.ApiException
import jakarta.ws.rs.core.Response

class UnauthorizedException(message: String, code: String = "UNAUTHORIZED") : ApiException(code, message, Response.Status.UNAUTHORIZED)
class ForbiddenException(message: String, code: String = "FORBIDDEN") : ApiException(code, message, Response.Status.FORBIDDEN)
class BadRequestException(message: String, code: String = "BAD_REQUEST") : ApiException(code, message, Response.Status.BAD_REQUEST)
class NotFoundException(message: String, code: String = "NOT_FOUND") : ApiException(code, message, Response.Status.NOT_FOUND)
class ConflictException(message: String, code: String = "CONFLICT") : ApiException(code, message, Response.Status.CONFLICT)
class PayloadTooLargeException(message: String, code: String = "UPLOAD_TOO_LARGE") : ApiException(code, message, Response.Status.REQUEST_ENTITY_TOO_LARGE)
