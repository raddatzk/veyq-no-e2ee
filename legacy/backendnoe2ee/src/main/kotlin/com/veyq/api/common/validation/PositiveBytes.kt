package com.veyq.api.common.validation

import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import kotlin.reflect.KClass

@Target(AnnotationTarget.FIELD, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
@Constraint(validatedBy = [PositiveBytesValidator::class])
annotation class PositiveBytes(
    val message: String = "sizeBytes must be greater than zero",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = [],
)

class PositiveBytesValidator : ConstraintValidator<PositiveBytes, Long> {
    override fun isValid(value: Long?, context: ConstraintValidatorContext?): Boolean =
        value == null || value > 0
}
