package com.lunva.api.auth.bcrypt

interface BcryptCostProvider {
    fun currentCost(): Int
}
