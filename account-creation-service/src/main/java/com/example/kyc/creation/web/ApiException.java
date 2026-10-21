package com.example.kyc.creation.web;

import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/** Business error that maps to an RFC 7807 problem with a stable {@code code} property. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final transient List<Dtos.FieldError> errors;
    private final UUID existingApplicationId;

    private ApiException(HttpStatus status, String code, String message,
                         List<Dtos.FieldError> errors, UUID existingApplicationId) {
        super(message);
        this.status = status;
        this.code = code;
        this.errors = errors;
        this.existingApplicationId = existingApplicationId;
    }

    public static ApiException validation(List<Dtos.FieldError> errors) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed", errors, null);
    }

    public static ApiException duplicatePan() {
        return new ApiException(HttpStatus.BAD_REQUEST, "DUPLICATE_PAN_IN_APPLICATION",
                "Primary applicant, nominee and guarantor must have different PANs", null, null);
    }

    public static ApiException identityImmutable(List<Dtos.FieldError> errors) {
        return new ApiException(HttpStatus.BAD_REQUEST, "IDENTITY_FIELD_IMMUTABLE",
                "name, pan and aadhaar cannot be changed", errors, null);
    }

    public static ApiException activeExists(UUID existing) {
        return new ApiException(HttpStatus.CONFLICT, "ACTIVE_APPLICATION_EXISTS",
                "A live application already exists for this primary PAN", null, existing);
    }

    public static ApiException notFound(UUID id) {
        return new ApiException(HttpStatus.NOT_FOUND, "APPLICATION_NOT_FOUND",
                "Application " + id + " not found", null, null);
    }

    public static ApiException concurrentModification() {
        return new ApiException(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION",
                "The application was modified concurrently; retry", null, null);
    }

    public HttpStatus status() { return status; }

    public String code() { return code; }

    public List<Dtos.FieldError> errors() { return errors; }

    public UUID existingApplicationId() { return existingApplicationId; }
}
