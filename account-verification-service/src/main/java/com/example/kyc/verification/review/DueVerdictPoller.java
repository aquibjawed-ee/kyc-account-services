package com.example.kyc.verification.review;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Scheduled, DB-only. Survives restarts because the due time lives on the review record. */
@Component
public class DueVerdictPoller {

    private static final Logger log = LoggerFactory.getLogger(DueVerdictPoller.class);

    private final ReviewService reviews;

    public DueVerdictPoller(ReviewService reviews) {
        this.reviews = reviews;
    }

    @Scheduled(fixedDelayString = "${kyc.review.poll-interval}")
    public void poll() {
        try {
            reviews.releaseDueVerdicts();
        } catch (RuntimeException e) {
            log.error("event=review.poll_failed", e);
        }
    }
}
