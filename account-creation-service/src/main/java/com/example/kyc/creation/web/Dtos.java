package com.example.kyc.creation.web;

import com.example.kyc.creation.domain.KycStatus;
import com.example.kyc.creation.validation.ValidAadhaar;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** HTTP request/response shapes. Field names follow the RFC's HTTP contract. */
public final class Dtos {

    public static final String PAN_REGEX = "^[A-Z]{5}[0-9]{4}[A-Z]$";

    private Dtos() {}

    public record PersonRequest(
            @NotBlank @Size(max = 200) String name,
            @NotBlank String address,
            @NotBlank @Pattern(regexp = PAN_REGEX, message = "must match [A-Z]{5}[0-9]{4}[A-Z]") String pan,
            @NotBlank @ValidAadhaar String aadhaar,
            @NotBlank @Size(max = 200) String parentsName,
            @NotNull @DecimalMin("0") BigDecimal annualIncome,
            @NotBlank @Size(max = 100) String incomeSource) {}

    public record CreateAccountRequest(
            @NotNull @Valid PersonRequest primary,
            @NotNull @Valid PersonRequest nominee,
            @NotNull @Valid PersonRequest guarantor) {}

    /** Non-identity fields only. Each field is optional; when present it must be valid. */
    public record PersonPatch(
            @Pattern(regexp = ".*\\S.*", message = "must not be blank") String address,
            @Pattern(regexp = ".*\\S.*", message = "must not be blank") @Size(max = 200) String parentsName,
            @DecimalMin("0") BigDecimal annualIncome,
            @Pattern(regexp = ".*\\S.*", message = "must not be blank") @Size(max = 100) String incomeSource) {

        public boolean isEmpty() {
            return address == null && parentsName == null && annualIncome == null && incomeSource == null;
        }
    }

    public record PatchAccountRequest(PersonPatch primary, PersonPatch nominee, PersonPatch guarantor) {}

    public record PersonResponse(UUID id, String name, String address, String pan, String aadhaar,
                                 String parentsName, BigDecimal annualIncome, String incomeSource) {}

    public record ApplicationResponse(UUID applicationId, KycStatus kycStatus, String statusReason,
                                      Instant slaBreachDetectedAt, Instant createdAt, Instant updatedAt,
                                      PersonResponse primary, PersonResponse nominee, PersonResponse guarantor) {}

    public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {}

    public record FieldError(String field, String message) {}
}
