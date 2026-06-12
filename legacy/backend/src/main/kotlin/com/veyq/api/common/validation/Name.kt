package com.veyq.api.common.validation

import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import kotlin.reflect.KClass

@Target(AnnotationTarget.FIELD, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
@Constraint(validatedBy = [NameValidator::class])
@Normalize
annotation class Name(
    val message: String = "name is invalid",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = [],
)

class NameValidator : ConstraintValidator<Name, String> {
    override fun isValid(value: String?, context: ConstraintValidatorContext?): Boolean {
        val normalized = value?.trim()?.lowercase() ?: return true
        return normalized.length <= MAX_NAME_LENGTH && VALID_NAME.matches(normalized) && normalized !in RESERVED_NAMES
    }
}

const val MAX_NAME_LENGTH = 120
private val VALID_NAME = Regex("^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$")
private val RESERVED_NAMES = setOf("settings")
