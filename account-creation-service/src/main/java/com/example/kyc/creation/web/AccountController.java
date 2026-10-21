package com.example.kyc.creation.web;

import com.example.kyc.creation.domain.KycStatus;
import com.example.kyc.creation.service.AccountService;
import com.fasterxml.jackson.databind.JsonNode;
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
public class AccountController {

    private final AccountService service;
    private final PatchParser patchParser;

    public AccountController(AccountService service, PatchParser patchParser) {
        this.service = service;
        this.patchParser = patchParser;
    }

    @PostMapping
    public ResponseEntity<Dtos.ApplicationResponse> create(@Valid @RequestBody Dtos.CreateAccountRequest request) {
        Dtos.ApplicationResponse created = service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/accounts/" + created.applicationId())).body(created);
    }

    @GetMapping("/{applicationId}")
    public Dtos.ApplicationResponse get(@PathVariable UUID applicationId) {
        return service.get(applicationId);
    }

    @GetMapping
    public Dtos.PageResponse<Dtos.ApplicationResponse> list(
            @RequestParam(required = false) KycStatus kycStatus,
            @RequestParam(required = false) Boolean slaBreached,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.list(kycStatus, slaBreached, page, size);
    }

    @PatchMapping("/{applicationId}")
    public Dtos.ApplicationResponse patch(@PathVariable UUID applicationId, @RequestBody JsonNode body) {
        return service.patch(applicationId, patchParser.parse(body));
    }

    @DeleteMapping("/{applicationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID applicationId) {
        service.delete(applicationId);
    }
}
