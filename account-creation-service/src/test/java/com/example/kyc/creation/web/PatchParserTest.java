package com.example.kyc.creation.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PatchParserTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final PatchParser parser =
            new PatchParser(mapper, Validation.buildDefaultValidatorFactory().getValidator());

    private JsonNode json(String s) throws Exception {
        return mapper.readTree(s);
    }

    @Test
    void acceptsParentsNameAndOtherNonIdentityFields() throws Exception {
        Dtos.PatchAccountRequest p = parser.parse(json("""
                {"nominee": {"parentsName": "Sita Rao", "address": "1 New Rd", "annualIncome": 10, "incomeSource": "Rent"}}"""));
        assertThat(p.nominee().parentsName()).isEqualTo("Sita Rao");
        assertThat(p.nominee().address()).isEqualTo("1 New Rd");
        assertThat(p.primary()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"name", "pan", "aadhaar"})
    void rejectsIdentityFieldsForAnyRole(String field) {
        for (String role : new String[] {"primary", "nominee", "guarantor"}) {
            assertThatThrownBy(() -> parser.parse(json("{\"" + role + "\": {\"" + field + "\": \"x\"}}")))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.code()).isEqualTo("IDENTITY_FIELD_IMMUTABLE");
                        assertThat(e.errors().get(0).field()).isEqualTo(role + "." + field);
                    });
        }
    }

    @Test
    void identityFieldWinsEvenAlongsideValidFields() {
        assertThatThrownBy(() -> parser.parse(json("{\"primary\": {\"parentsName\": \"A\", \"pan\": \"ABCDE1234F\"}}")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("IDENTITY_FIELD_IMMUTABLE"));
    }

    @Test
    void rejectsUnknownPropertiesAndBadValues() {
        assertThatThrownBy(() -> parser.parse(json("{\"primary\": {\"shoeSize\": 9}}")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("VALIDATION_FAILED"));
        assertThatThrownBy(() -> parser.parse(json("{\"owner\": {}}")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("VALIDATION_FAILED"));
        assertThatThrownBy(() -> parser.parse(json("{\"primary\": {\"annualIncome\": -5}}")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("VALIDATION_FAILED"));
        assertThatThrownBy(() -> parser.parse(json("{\"primary\": {\"annualIncome\": \"lots\"}}")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("VALIDATION_FAILED"));
        assertThatThrownBy(() -> parser.parse(json("{\"primary\": {\"address\": \"  \"}}")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("VALIDATION_FAILED"));
    }

    @Test
    void emptyObjectIsANoOpAndNonObjectIsRejected() throws Exception {
        Dtos.PatchAccountRequest p = parser.parse(json("{}"));
        assertThat(p.primary()).isNull();
        assertThat(p.nominee()).isNull();
        assertThat(p.guarantor()).isNull();
        assertThatThrownBy(() -> parser.parse(json("[1]")))
                .isInstanceOf(ApiException.class);
    }
}
