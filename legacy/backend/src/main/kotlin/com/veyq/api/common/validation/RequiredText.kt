package com.veyq.api.common.validation

import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import kotlin.reflect.KClass

@Target(AnnotationTarget.FIELD, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
@Constraint(validatedBy = [RequiredTextValidator::class])
annotation class RequiredText(
    val message: String = "value must not be blank",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = [],
)

class RequiredTextValidator : ConstraintValidator<RequiredText, String> {
    override fun isValid(value: String?, context: ConstraintValidatorContext?): Boolean =
        value == null || value.isNotBlank()
}
