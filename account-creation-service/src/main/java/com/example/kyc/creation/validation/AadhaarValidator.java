package com.example.kyc.creation.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.regex.Pattern;

public class AadhaarValidator implements ConstraintValidator<ValidAadhaar, String> {

    private static final Pattern FORMAT = Pattern.compile("^[2-9][0-9]{11}$");

    public static boolean isValid(String value) {
        return value != null && FORMAT.matcher(value).matches() && Verhoeff.isValid(value);
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value == null || isValid(value);
    }
}
