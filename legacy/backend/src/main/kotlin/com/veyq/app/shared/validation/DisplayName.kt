package com.veyq.app.shared.validation

import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import kotlin.reflect.KClass

const val MAX_DISPLAY_NAME_LENGTH = 120

/**
 * A non-blank display name, at most [MAX_DISPLAY_NAME_LENGTH] chars. Applied to request fields via the OpenAPI
 * `x-field-extra-annotation` vendor extension: the `kotlin` model generator drops standard maxLength/pattern
 * constraints, so a custom `@Constraint` (emitted onto the model field as `@field:…`) is how field-level
 * validation is actually enforced at the `@Valid` boundary. See developer-styleguide §4.
 */
@Target(AnnotationTarget.FIELD, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
@Constraint(validatedBy = [DisplayNameValidator::class])
annotation class DisplayName(
    val message: String = "displayName must be non-blank and at most 120 characters",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = [],
)

class DisplayNameValidator : ConstraintValidator<DisplayName, String> {
    override fun isValid(value: String?, context: ConstraintValidatorContext?): Boolean =
        value == null || (value.isNotBlank() && value.length <= MAX_DISPLAY_NAME_LENGTH)
}
