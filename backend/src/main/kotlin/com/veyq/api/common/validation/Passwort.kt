package com.veyq.api.common.validation

import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import kotlin.reflect.KClass

@Target(AnnotationTarget.FIELD, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
@jakarta.validation.Constraint(validatedBy = [PasswordValidator::class])
annotation class Password(
    val message: String = "password does not meet password rules",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = [],
)

const val MIN_PASSWORD_LENGTH = 10

class PasswordValidator : ConstraintValidator<Password, String> {
    override fun isValid(value: String?, context: ConstraintValidatorContext?): Boolean {
        return value == null || value.length >= MIN_PASSWORD_LENGTH
    }
}
