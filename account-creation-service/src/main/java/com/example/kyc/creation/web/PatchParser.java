package com.example.kyc.creation.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Parses a PATCH body strictly: identity fields (name, pan, aadhaar) are rejected with
 * IDENTITY_FIELD_IMMUTABLE, any other unknown property with VALIDATION_FAILED.
 */
@Component
public class PatchParser {

    private static final Set<String> IDENTITY = Set.of("name", "pan", "aadhaar");
    private static final Set<String> EDITABLE = Set.of("address", "parentsName", "annualIncome", "incomeSource");
    private static final List<String> ROLES = List.of("primary", "nominee", "guarantor");

    private final ObjectMapper mapper;
    private final Validator validator;

    public PatchParser(ObjectMapper mapper, Validator validator) {
        this.mapper = mapper;
        this.validator = validator;
    }

    public Dtos.PatchAccountRequest parse(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw ApiException.validation(List.of(new Dtos.FieldError("body", "must be a JSON object")));
        }
        List<Dtos.FieldError> immutable = new ArrayList<>();
        List<Dtos.FieldError> invalid = new ArrayList<>();

        for (Iterator<String> roles = body.fieldNames(); roles.hasNext(); ) {
            String role = roles.next();
            if (!ROLES.contains(role)) {
                invalid.add(new Dtos.FieldError(role, "unknown property"));
                continue;
            }
            JsonNode person = body.get(role);
            if (person.isNull()) continue;
            if (!person.isObject()) {
                invalid.add(new Dtos.FieldError(role, "must be an object"));
                continue;
            }
            for (Iterator<String> fields = person.fieldNames(); fields.hasNext(); ) {
                String field = fields.next();
                if (IDENTITY.contains(field)) {
                    immutable.add(new Dtos.FieldError(role + "." + field, "cannot be changed"));
                } else if (!EDITABLE.contains(field)) {
                    invalid.add(new Dtos.FieldError(role + "." + field, "unknown property"));
                }
            }
        }
        if (!immutable.isEmpty()) throw ApiException.identityImmutable(immutable);
        if (!invalid.isEmpty()) throw ApiException.validation(invalid);

        Map<String, Dtos.PersonPatch> patches = new java.util.HashMap<>();
        for (String role : ROLES) {
            JsonNode node = body.get(role);
            if (node == null || node.isNull()) continue;
            try {
                Dtos.PersonPatch patch = mapper.treeToValue(node, Dtos.PersonPatch.class);
                for (ConstraintViolation<Dtos.PersonPatch> v : validator.validate(patch)) {
                    invalid.add(new Dtos.FieldError(role + "." + v.getPropertyPath(), v.getMessage()));
                }
                patches.put(role, patch);
            } catch (JsonProcessingException e) {
                invalid.add(new Dtos.FieldError(role, "contains a value of the wrong type"));
            }
        }
        // An empty object is a valid no-op PATCH (200, nothing changes).
        if (!invalid.isEmpty()) throw ApiException.validation(invalid);
        return new Dtos.PatchAccountRequest(patches.get("primary"), patches.get("nominee"), patches.get("guarantor"));
    }
}
