package com.example.kyc.creation.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 12 digits, first digit 2-9, Verhoeff checksum valid. Null is valid (pair with @NotBlank). */
@Documented
@Constraint(validatedBy = AadhaarValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidAadhaar {
    String message() default "must be a valid 12-digit Aadhaar number";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
