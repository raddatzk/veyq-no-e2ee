package com.veyq.api.auth.bcrypt

interface BcryptCostProvider {
    fun currentCost(): Int
}
