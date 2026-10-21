package com.example.kyc.creation.service;

import com.example.kyc.creation.domain.KycStateMachine;
import com.example.kyc.creation.domain.KycStateMachine.Event;
import com.example.kyc.creation.domain.KycStateMachine.Result;
import com.example.kyc.creation.domain.KycStatus;
import com.example.kyc.creation.messaging.Messages;
import com.example.kyc.creation.repo.AccountRepository;
import com.example.kyc.creation.repo.AccountRepository.KycRow;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies ack/verdict messages to the KYC state. Dedupe (processed_message) and the state change share one
 * transaction, so a duplicate send never changes state twice and a failed apply never burns the dedupe row.
 */
@Service
public class KycTransitionService {

    private static final Logger log = LoggerFactory.getLogger(KycTransitionService.class);

    private final AccountRepository repo;
    private final JdbcTemplate jdbc;
    private final MeterRegistry metrics;

    public KycTransitionService(AccountRepository repo, JdbcTemplate jdbc, MeterRegistry metrics) {
        this.repo = repo;
        this.jdbc = jdbc;
        this.metrics = metrics;
    }

    @Transactional
    public void onAck(Messages.KycAck ack) {
        handle(ack.applicationId(), Messages.KYC_ACK, Event.ACK, ack.reviewId(), null);
    }

    @Transactional
    public void onVerdict(Messages.KycVerdict v) {
        Event event = v.outcome() == Messages.VerdictOutcome.PASSED ? Event.VERDICT_PASSED : Event.VERDICT_FAILED;
        handle(v.applicationId(), Messages.KYC_VERDICT, event, v.reviewId(), v.reason());
    }

    private void handle(UUID applicationId, String type, Event event, UUID reviewId, String reason) {
        int inserted = jdbc.update("""
                INSERT INTO processed_message(application_id, message_type, processed_at) VALUES (?,?, now())
                ON CONFLICT DO NOTHING""", applicationId, type);
        if (inserted == 0) {
            log.debug("event=message.duplicate_skipped applicationId={} type={}", applicationId, type);
            metrics.counter("kyc.message.duplicate", "type", type).increment();
            return;
        }
        Optional<KycRow> found = repo.findKyc(applicationId);
        if (found.isEmpty() || found.get().deletedAt() != null) {
            log.info("event=message.dropped_unknown_or_deleted applicationId={} type={}", applicationId, type);
            return;
        }
        KycRow row = found.get();
        Result result = KycStateMachine.apply(row.status(), event);
        switch (result.outcome()) {
            case IGNORED -> log.debug("event=transition.ignored applicationId={} status={} event={}",
                    applicationId, row.status(), event);
            case REJECTED -> {
                log.warn("event=transition.rejected applicationId={} from={} event={}", applicationId,
                        row.status(), event);
                metrics.counter("kyc.transition.rejected", "from", row.status().name(), "to", target(event)).increment();
            }
            case APPLIED -> {
                if (repo.applyTransition(applicationId, row.version(), result.next(), reason, reviewId) == 0) {
                    // Another writer won; roll back (including the dedupe row) and let the error handler retry.
                    throw new OptimisticLockingFailureException("kyc_details version moved for " + applicationId);
                }
                log.info("event=transition.applied applicationId={} from={} to={}", applicationId, row.status(),
                        result.next());
            }
        }
    }

    private static String target(Event event) {
        return switch (event) {
            case ACK -> KycStatus.MANUAL_REVIEW_PENDING.name();
            case VERDICT_PASSED -> KycStatus.KYC_DONE.name();
            case VERDICT_FAILED -> KycStatus.REVIEW_FAILED.name();
            case REQUEST_RELAYED -> KycStatus.IN_PROGRESS.name();
        };
    }
}
