package com.veyq.api.auth.argon2

data class Argon2Parameters(
    val iterations: Int,
    val memoryKiB: Int,
    val parallelism: Int,
) {
    fun serialize(): String = "m=$memoryKiB,t=$iterations,p=$parallelism"

    companion object {
        fun deserialize(value: String): Argon2Parameters {
            val parts = value.split(",")
                .mapNotNull {
                    val keyValue = it.split("=", limit = 2)
                    if (keyValue.size == 2) keyValue[0] to keyValue[1].toIntOrNull() else null
                }
                .toMap()

            return Argon2Parameters(
                iterations = parts["t"] ?: throw IllegalArgumentException("Missing Argon2 iterations"),
                memoryKiB = parts["m"] ?: throw IllegalArgumentException("Missing Argon2 memory"),
                parallelism = parts["p"] ?: throw IllegalArgumentException("Missing Argon2 parallelism"),
            )
        }
    }
}
