package com.veyq.app.shared.validation

import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import kotlin.reflect.KClass

/**
 * A syntactically valid email address. Applied to request fields via the OpenAPI `x-field-extra-annotation`
 * vendor extension (see [DisplayName] for why a custom `@Constraint` is needed). Null/absent is left to
 * `@NotNull`/`required`; this only checks shape when a value is present.
 */
@Target(AnnotationTarget.FIELD, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
@Constraint(validatedBy = [EmailValidator::class])
annotation class Email(
    val message: String = "email is not a valid address",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = [],
)

class EmailValidator : ConstraintValidator<Email, String> {
    override fun isValid(value: String?, context: ConstraintValidatorContext?): Boolean =
        value == null || (value.length <= 320 && REGEX.matches(value))

    companion object {
        // Pragmatic RFC-5321-ish: one @, a local part, and a dotted domain. Not a full grammar — just rejects
        // obvious garbage (no @, spaces, empty parts) before the operator-blind handlers see it.
        val REGEX = Regex("^[^@\\s]+@[^@\\s.]+(?:\\.[^@\\s.]+)+$")
    }
}
