package com.example.kyc.creation.web;

import com.example.kyc.creation.domain.KycStatus;
import com.example.kyc.creation.service.AccountService;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/accounts")
@Tag(name = "Account applications", description = "CRUD over the account application and its KYC state")
public class AccountController {

    private final AccountService service;
    private final PatchParser patchParser;

    public AccountController(AccountService service, PatchParser patchParser) {
        this.service = service;
        this.patchParser = patchParser;
    }

    @Operation(summary = "Create an account application",
            description = "Validates and persists the applicant, nominee and guarantor, then returns at once "
                    + "with kycStatus=STARTED. The KYC request is written to the outbox in the same "
                    + "transaction and relayed to Kafka by the scheduled relay, so the caller never waits "
                    + "on verification. PAN must match the standard format and Aadhaar must pass the "
                    + "Verhoeff checksum; the three people must have different PANs.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Created, kycStatus=STARTED"),
            @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED or DUPLICATE_PAN_IN_APPLICATION"),
            @ApiResponse(responseCode = "409", description = "ACTIVE_APPLICATION_EXISTS; the body carries "
                    + "existingApplicationId so a client whose POST timed out can recover")})
    @PostMapping
    public ResponseEntity<Dtos.ApplicationResponse> create(@Valid @RequestBody Dtos.CreateAccountRequest request) {
        Dtos.ApplicationResponse created = service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/accounts/" + created.applicationId())).body(created);
    }

    @Operation(summary = "Fetch one application",
            description = "Returns the application with its current kycStatus, statusReason once a verdict "
                    + "has arrived, and slaBreachDetectedAt if the SLA sweep flagged it. Soft-deleted "
                    + "applications return 404.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Found"),
            @ApiResponse(responseCode = "404", description = "Unknown or soft-deleted")})
    @GetMapping("/{applicationId}")
    public Dtos.ApplicationResponse get(@PathVariable UUID applicationId) {
        return service.get(applicationId);
    }

    @Operation(summary = "List applications",
            description = "Pageable list, optionally filtered by kycStatus and by slaBreached=true, which "
                    + "is how you find applications that never received a verdict inside the SLA window.")
    @GetMapping
    public Dtos.PageResponse<Dtos.ApplicationResponse> list(
            @RequestParam(required = false) KycStatus kycStatus,
            @RequestParam(required = false) Boolean slaBreached,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.list(kycStatus, slaBreached, page, size);
    }

    @Operation(summary = "Update non-identity fields",
            description = "Address, annual income, income source and parents' name can be changed at any "
                    + "time. Name, PAN and Aadhaar are immutable because they are exactly what the "
                    + "verification service checks, so a change would differ from what was verified. An "
                    + "empty body is a no-op returning 200; unknown properties are rejected.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Updated, or unchanged for an empty body"),
            @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED or IDENTITY_FIELD_IMMUTABLE"),
            @ApiResponse(responseCode = "404", description = "Unknown or soft-deleted"),
            @ApiResponse(responseCode = "409", description = "CONCURRENT_MODIFICATION")})
    @PatchMapping("/{applicationId}")
    public Dtos.ApplicationResponse patch(@PathVariable UUID applicationId, @RequestBody JsonNode body) {
        return service.patch(applicationId, patchParser.parse(body));
    }

    @Operation(summary = "Soft-delete an application",
            description = "Marks the application deleted. Verification is NOT told to stop: it runs to "
                    + "completion and any late acknowledgement or verdict is logged and dropped. The SLA "
                    + "sweep ignores deleted applications, and the PAN is free for a new application.")
    @ApiResponse(responseCode = "204", description = "Deleted")
    @DeleteMapping("/{applicationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID applicationId) {
        service.delete(applicationId);
    }
}
