package com.veyq.app.platform.error

/** The two trivial API errors that were re-declared as private helpers in nearly every handler. */
fun unauthorized() = ApiException(401, "UNAUTHORIZED", "Missing or invalid session")

fun notFound(what: String) = ApiException(404, "NOT_FOUND", "$what not found")
