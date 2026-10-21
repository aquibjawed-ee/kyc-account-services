package com.example.kyc.creation.web;

import jakarta.validation.ConstraintViolationException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Every error is application/problem+json with a {@code code}; stack traces never reach the client. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> api(ApiException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(e.status(), e.getMessage());
        pd.setProperty("code", e.code());
        if (e.errors() != null) pd.setProperty("errors", e.errors());
        if (e.existingApplicationId() != null) pd.setProperty("existingApplicationId", e.existingApplicationId());
        log.info("event=api.error code={} status={}", e.code(), e.status().value());
        return ResponseEntity.status(e.status()).body(pd);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> invalid(MethodArgumentNotValidException e) {
        List<Dtos.FieldError> errors = e.getBindingResult().getFieldErrors().stream()
                .map(f -> new Dtos.FieldError(f.getField(), f.getDefaultMessage())).toList();
        return api(ApiException.validation(errors));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ProblemDetail> constraint(ConstraintViolationException e) {
        List<Dtos.FieldError> errors = e.getConstraintViolations().stream()
                .map(v -> new Dtos.FieldError(v.getPropertyPath().toString(), v.getMessage())).toList();
        return api(ApiException.validation(errors));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ProblemDetail> unreadable(Exception e) {
        return api(ApiException.validation(List.of(new Dtos.FieldError("body", "malformed or unreadable request"))));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception e) {
        if (e instanceof ErrorResponse er) { // framework 404/405/415 etc. keep their own status
            ProblemDetail body = er.getBody();
            body.setProperty("code", "REQUEST_REJECTED");
            return ResponseEntity.status(er.getStatusCode()).body(body);
        }
        log.error("event=api.unexpected_error", e);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error");
        pd.setProperty("code", "INTERNAL_ERROR");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(pd);
    }
}
