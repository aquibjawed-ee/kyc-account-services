package com.example.kyc.verification.review;

import java.util.UUID;

/** Decides pass / fail / no-reply for an application. Tests replace this bean to force an outcome. */
public interface OutcomeSelector {

    enum PlannedOutcome { PASS, FAIL, NO_REPLY }

    PlannedOutcome select(UUID applicationId);
}
