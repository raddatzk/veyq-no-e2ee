package com.veyq.api.tenant

object TenantName {
    private val validPattern = Regex("^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$")

    val reserved = setOf(
        "api",
        "auth",
        "users",
        "user",
        "me",
        "setup",
        "admin",
        "tenants",
        "static",
        "assets",
        "health",
        "q",
    )

    fun isValid(value: String): Boolean {
        return value.length <= 120 && value.matches(validPattern) && value !in reserved
    }
}
