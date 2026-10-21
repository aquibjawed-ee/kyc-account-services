package com.example.kyc.creation.service;

import com.example.kyc.creation.config.KycProperties;
import com.example.kyc.creation.domain.KycStatus;
import com.example.kyc.creation.domain.Role;
import com.example.kyc.creation.messaging.Messages;
import com.example.kyc.creation.outbox.OutboxStore;
import com.example.kyc.creation.repo.AccountRepository;
import com.example.kyc.creation.repo.AccountRepository.KycRow;
import com.example.kyc.creation.web.ApiException;
import com.example.kyc.creation.web.Dtos;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Application CRUD. Create is one DB transaction (application + outbox row); it never touches Kafka
 * or the identity provider, so the caller never waits on verification.
 */
@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);
    private static final int MAX_PAGE_SIZE = 100;

    private final AccountRepository repo;
    private final OutboxStore outbox;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final KycProperties props;

    public AccountService(AccountRepository repo, OutboxStore outbox, TransactionTemplate tx, ObjectMapper mapper,
                          KycProperties props) {
        this.repo = repo;
        this.outbox = outbox;
        this.tx = tx;
        this.mapper = mapper;
        this.props = props;
    }

    public Dtos.ApplicationResponse create(Dtos.CreateAccountRequest req) {
        String primaryPan = req.primary().pan();
        if (primaryPan.equals(req.nominee().pan()) || primaryPan.equals(req.guarantor().pan())
                || req.nominee().pan().equals(req.guarantor().pan())) {
            throw ApiException.duplicatePan();
        }
        try {
            Dtos.ApplicationResponse created = tx.execute(status -> doCreate(req));
            log.info("event=application.created applicationId={} kycStatus={}", created.applicationId(),
                    created.kycStatus());
            return created;
        } catch (DuplicateKeyException e) {
            // Lost the race with a concurrent create: re-read in a fresh transaction (this one is aborted).
            UUID existing = repo.findLiveIdByPrimaryPan(primaryPan).orElseThrow(() -> e);
            log.info("event=application.create_conflict existingApplicationId={}", existing);
            throw ApiException.activeExists(existing);
        }
    }

    private Dtos.ApplicationResponse doCreate(Dtos.CreateAccountRequest req) {
        String primaryPan = req.primary().pan();
        repo.findLiveIdByPrimaryPan(primaryPan).ifPresent(existing -> {
            throw ApiException.activeExists(existing);
        });
        Instant now = Instant.now();
        UUID applicationId = UUID.randomUUID();
        repo.insertKyc(applicationId, primaryPan, now);

        Map<Role, Dtos.PersonRequest> people = Map.of(Role.PRIMARY, req.primary(), Role.NOMINEE, req.nominee(),
                Role.GUARANTOR, req.guarantor());
        Map<Role, UUID> ids = new java.util.EnumMap<>(Role.class);
        List<Messages.PersonIdentity> identities = new ArrayList<>();
        for (Role role : Role.values()) {
            Dtos.PersonRequest p = people.get(role);
            UUID userId = UUID.randomUUID();
            ids.put(role, userId);
            repo.insertUser(userId, p, now);
            repo.insertRelationship(applicationId, userId, role);
            identities.add(new Messages.PersonIdentity(role.name(), p.name(), p.pan(), p.aadhaar()));
        }
        Messages.KycRequest msg = new Messages.KycRequest(UUID.randomUUID(), applicationId, now, identities);
        try {
            outbox.enqueue(applicationId, props.topics().request(), OutboxStore.KYC_REQUEST,
                    mapper.writeValueAsString(msg));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialise KYC request", e);
        }
        return new Dtos.ApplicationResponse(applicationId, KycStatus.STARTED, null, null, now, now,
                person(ids.get(Role.PRIMARY), req.primary()), person(ids.get(Role.NOMINEE), req.nominee()),
                person(ids.get(Role.GUARANTOR), req.guarantor()));
    }

    private static Dtos.PersonResponse person(UUID id, Dtos.PersonRequest p) {
        return new Dtos.PersonResponse(id, p.name(), p.address(), p.pan(), p.aadhaar(), p.parentsName(),
                p.annualIncome(), p.incomeSource());
    }

    public Dtos.ApplicationResponse get(UUID applicationId) {
        KycRow row = repo.findKyc(applicationId).filter(r -> r.deletedAt() == null)
                .orElseThrow(() -> ApiException.notFound(applicationId));
        return toResponse(row);
    }

    public Dtos.PageResponse<Dtos.ApplicationResponse> list(KycStatus status, Boolean slaBreached, int page,
                                                            int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        List<Dtos.ApplicationResponse> content = repo.listLiveIds(status, slaBreached, safePage, safeSize).stream()
                .map(id -> repo.findKyc(id).map(this::toResponse).orElse(null)).filter(Objects::nonNull).toList();
        long total = repo.countLive(status, slaBreached);
        return new Dtos.PageResponse<>(content, safePage, safeSize, total, (int) Math.ceil((double) total / safeSize));
    }

    public Dtos.ApplicationResponse patch(UUID applicationId, Dtos.PatchAccountRequest patch) {
        tx.executeWithoutResult(status -> {
            KycRow row = repo.findKyc(applicationId).filter(r -> r.deletedAt() == null)
                    .orElseThrow(() -> ApiException.notFound(applicationId));
            applyPatch(applicationId, Role.PRIMARY, patch.primary());
            applyPatch(applicationId, Role.NOMINEE, patch.nominee());
            applyPatch(applicationId, Role.GUARANTOR, patch.guarantor());
            if (repo.bumpVersion(applicationId, row.version()) == 0) {
                throw ApiException.concurrentModification(); // rolls back the person updates too
            }
        });
        log.info("event=application.updated applicationId={}", applicationId);
        return get(applicationId); // edits never restart KYC
    }

    private void applyPatch(UUID applicationId, Role role, Dtos.PersonPatch p) {
        if (p != null && !p.isEmpty()) repo.updatePerson(applicationId, role, p);
    }

    public void delete(UUID applicationId) {
        if (repo.softDelete(applicationId) == 0) throw ApiException.notFound(applicationId);
        // Verification is deliberately not told; late messages for this application are dropped.
        log.info("event=application.soft_deleted applicationId={}", applicationId);
    }

    private Dtos.ApplicationResponse toResponse(KycRow row) {
        Map<Role, Dtos.PersonResponse> people = repo.loadPersons(row.applicationId());
        return new Dtos.ApplicationResponse(row.applicationId(), row.status(), row.reason(),
                row.slaBreachDetectedAt(), row.createdAt(), row.updatedAt(), people.get(Role.PRIMARY),
                people.get(Role.NOMINEE), people.get(Role.GUARANTOR));
    }
}
