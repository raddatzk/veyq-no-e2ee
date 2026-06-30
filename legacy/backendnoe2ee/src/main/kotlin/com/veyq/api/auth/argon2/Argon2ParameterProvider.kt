package com.veyq.api.auth.argon2

interface Argon2ParameterProvider {
    fun currentParameters(): Argon2Parameters
}
