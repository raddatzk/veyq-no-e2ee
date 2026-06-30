package com.veyq.api.common.validation

import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import kotlin.reflect.KClass

@Target(AnnotationTarget.FIELD, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
@Constraint(validatedBy = [DisplayNameValidator::class])
annotation class DisplayName(
    val message: String = "displayName must not be blank",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = [],
)

class DisplayNameValidator : ConstraintValidator<DisplayName, String> {
    override fun isValid(value: String?, context: ConstraintValidatorContext?): Boolean =
        value == null || value.isNotBlank() && value.length <= MAX_DISPLAY_NAME_LENGTH
}

const val MAX_DISPLAY_NAME_LENGTH = 120
