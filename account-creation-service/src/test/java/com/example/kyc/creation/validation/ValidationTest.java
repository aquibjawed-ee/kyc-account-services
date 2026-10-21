package com.example.kyc.creation.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.kyc.creation.support.Fixtures;
import com.example.kyc.creation.web.Dtos;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private Dtos.PersonRequest person(String pan, String aadhaar) {
        return new Dtos.PersonRequest("Asha", "addr", pan, aadhaar, "Ravi", BigDecimal.TEN, "Salary");
    }

    private Set<String> violations(Dtos.PersonRequest p) {
        return validator.validate(p).stream().map(v -> v.getPropertyPath().toString()).collect(Collectors.toSet());
    }

    @RepeatedTest(20)
    void generatedAadhaarPassesVerhoeff() {
        assertThat(AadhaarValidator.isValid(Fixtures.validAadhaar())).isTrue();
    }

    @Test
    void knownVerhoeffVector() {
        assertThat(Verhoeff.isValid("2363")).isTrue(); // classic textbook vector: payload 236 + check digit 3
        assertThat(Verhoeff.checkDigit("236")).isEqualTo(3);
        assertThat(Verhoeff.isValid("2364")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "12345", "0123456789012", "123456789012", "1234567890AB", "199999999999"})
    void badAadhaarRejected(String aadhaar) {
        assertThat(AadhaarValidator.isValid(aadhaar)).isFalse();
    }

    @Test
    void aadhaarMustNotStartWithZeroOrOne() {
        String payload = "0" + "2345678901";
        String withCheck = payload + Verhoeff.checkDigit(payload);
        assertThat(Verhoeff.isValid(withCheck)).isTrue(); // checksum alone would accept it
        assertThat(AadhaarValidator.isValid(withCheck)).isFalse(); // the 2-9 leading digit rule rejects it
    }

    @ParameterizedTest
    @ValueSource(strings = {"abcde1234f", "ABCDE12345", "ABCD1234E", "ABCDE1234EE", "1BCDE1234F", ""})
    void malformedPanRejected(String pan) {
        assertThat(violations(person(pan, Fixtures.validAadhaar()))).contains("pan");
    }

    @Test
    void validPersonHasNoViolations() {
        assertThat(violations(person("ABCDE1234F", Fixtures.validAadhaar()))).isEmpty();
    }

    @Test
    void negativeIncomeAndBlankFieldsRejected() {
        Dtos.PersonRequest p = new Dtos.PersonRequest(" ", "addr", "ABCDE1234F", Fixtures.validAadhaar(), "Ravi",
                new BigDecimal("-1"), "");
        assertThat(violations(p)).contains("name", "annualIncome", "incomeSource");
    }
}
