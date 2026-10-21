package com.example.kyc.verification.support;

import com.example.kyc.verification.review.OutcomeSelector;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Test override: force the planned outcome per application; defaults to PASS. */
@Component
@Primary
@Profile("test")
public class ForcedOutcomeSelector implements OutcomeSelector {

    private final Map<UUID, PlannedOutcome> forced = new ConcurrentHashMap<>();

    public void force(UUID applicationId, PlannedOutcome outcome) {
        forced.put(applicationId, outcome);
    }

    @Override
    public PlannedOutcome select(UUID applicationId) {
        return forced.getOrDefault(applicationId, PlannedOutcome.PASS);
    }
}
