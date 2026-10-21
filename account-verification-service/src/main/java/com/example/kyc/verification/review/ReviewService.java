package com.example.kyc.verification.review;

import com.example.kyc.verification.config.KycProperties;
import com.example.kyc.verification.identity.IdentityProviderClient.IdentityResult;
import com.example.kyc.verification.messaging.Messages;
import com.example.kyc.verification.outbox.OutboxStore;
import com.example.kyc.verification.review.OutcomeSelector.PlannedOutcome;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Review records, the idempotent request handling transaction, and the due-verdict release. DB-only work. */
@Service
public class ReviewService {

    private static final Logger log = LoggerFactory.getLogger(ReviewService.class);

    static final List<String> FAIL_REASONS = List.of("Address proof inconsistent", "Income source unverifiable",
            "Supporting documents incomplete");
    static final String PASS_REASON = "Manual review passed";

    public record ReviewView(UUID reviewId, UUID applicationId, String status, String reason, Instant verdictDueAt,
                             Instant verdictPublishedAt, Instant createdAt) {}

    private final JdbcTemplate jdbc;
    private final OutboxStore outbox;
    private final OutcomeSelector selector;
    private final ObjectMapper mapper;
    private final KycProperties props;
    private final MeterRegistry metrics;

    public ReviewService(JdbcTemplate jdbc, OutboxStore outbox, OutcomeSelector selector, ObjectMapper mapper,
                         KycProperties props, MeterRegistry metrics) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.selector = selector;
        this.mapper = mapper;
        this.props = props;
        this.metrics = metrics;
    }

    public boolean alreadyProcessed(UUID applicationId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM processed_message WHERE application_id = ? AND message_type = ?",
                Integer.class, applicationId, Messages.KYC_REQUEST);
        return n != null && n > 0;
    }

    /**
     * One transaction: dedupe row + review record + outbox ack (+ outbox FAILED verdict on mismatch).
     * No transaction is held while the identity checks run; this is called after they finish.
     */
    @Transactional
    public void recordReview(Messages.KycRequest request, List<IdentityResult> results) {
        UUID applicationId = request.applicationId();
        int inserted = jdbc.update("""
                INSERT INTO processed_message(application_id, message_type, processed_at) VALUES (?,?, now())
                ON CONFLICT DO NOTHING""", applicationId, Messages.KYC_REQUEST);
        if (inserted == 0) {
            log.debug("event=message.duplicate_skipped applicationId={} type={}", applicationId, Messages.KYC_REQUEST);
            metrics.counter("kyc.message.duplicate", "type", Messages.KYC_REQUEST).increment();
            return;
        }
        UUID reviewId = UUID.randomUUID();
        IdentityResult mismatch = results.stream().filter(r -> !r.match()).findFirst().orElse(null);
        Instant now = Instant.now();
        try {
            if (mismatch != null) {
                String reason = "Identity mismatch for " + mismatch.role() + ": " + mismatch.reason();
                jdbc.update("""
                        INSERT INTO review_record(review_id, application_id, status, planned_outcome, reason,
                                                  verdict_published_at, created_at)
                        VALUES (?,?,?,?,?,?,?)""", reviewId, applicationId, "MANUAL_REVIEW_FAILED", "FAIL", reason,
                        Timestamp.from(now), Timestamp.from(now));
                enqueueAck(applicationId, reviewId, now);
                enqueueVerdict(applicationId, reviewId, Messages.VerdictOutcome.FAILED, reason, now);
                metrics.counter("kyc.verdict.outcome", "outcome", "fail").increment();
                log.info("event=review.identity_mismatch applicationId={} reviewId={} role={}", applicationId,
                        reviewId, mismatch.role());
                return;
            }
            PlannedOutcome planned = selector.select(applicationId);
            Timestamp due = planned == PlannedOutcome.NO_REPLY ? null : Timestamp.from(now.plus(randomDelay()));
            jdbc.update("""
                    INSERT INTO review_record(review_id, application_id, status, planned_outcome, verdict_due_at,
                                              created_at)
                    VALUES (?,?,?,?,?,?)""", reviewId, applicationId, "MANUAL_REVIEW_STARTED", planned.name(), due,
                    Timestamp.from(now));
            enqueueAck(applicationId, reviewId, now);
            if (planned == PlannedOutcome.NO_REPLY) {
                metrics.counter("kyc.verdict.outcome", "outcome", "no_reply").increment();
            }
            log.info("event=review.started applicationId={} reviewId={} planned={} dueAt={}", applicationId, reviewId,
                    planned, due == null ? null : due.toInstant());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialise outbox message", e);
        }
    }

    private Duration randomDelay() {
        long min = props.review().delayMin().toMillis();
        long max = props.review().delayMax().toMillis();
        return Duration.ofMillis(max <= min ? min : ThreadLocalRandom.current().nextLong(min, max + 1));
    }

    private void enqueueAck(UUID applicationId, UUID reviewId, Instant now) throws JsonProcessingException {
        outbox.enqueue(applicationId, props.topics().ack(), Messages.KYC_ACK,
                mapper.writeValueAsString(new Messages.KycAck(UUID.randomUUID(), applicationId, reviewId, now)));
    }

    private void enqueueVerdict(UUID applicationId, UUID reviewId, Messages.VerdictOutcome outcome, String reason,
                                Instant now) throws JsonProcessingException {
        outbox.enqueue(applicationId, props.topics().verdict(), Messages.KYC_VERDICT, mapper.writeValueAsString(
                new Messages.KycVerdict(UUID.randomUUID(), applicationId, reviewId, outcome, reason, now)));
    }

    /** Releases due verdicts: updates the review and writes the outbox row in one transaction; no network I/O. */
    @Transactional
    public int releaseDueVerdicts() {
        record Due(UUID reviewId, UUID applicationId, String planned) {}
        List<Due> due = jdbc.query("""
                SELECT review_id, application_id, planned_outcome FROM review_record
                WHERE verdict_due_at <= now() AND verdict_published_at IS NULL AND planned_outcome <> 'NO_REPLY'
                ORDER BY verdict_due_at LIMIT ? FOR UPDATE SKIP LOCKED""",
                (rs, n) -> new Due(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3)),
                props.review().pollBatchSize());
        Instant now = Instant.now();
        for (Due d : due) {
            boolean pass = "PASS".equals(d.planned());
            String reason = pass ? PASS_REASON
                    : FAIL_REASONS.get(Math.floorMod(d.applicationId().hashCode(), FAIL_REASONS.size()));
            jdbc.update("""
                    UPDATE review_record SET status = ?, reason = ?, verdict_published_at = ? WHERE review_id = ?""",
                    pass ? "MANUAL_REVIEW_PASSED" : "MANUAL_REVIEW_FAILED", reason, Timestamp.from(now), d.reviewId());
            try {
                enqueueVerdict(d.applicationId(), d.reviewId(),
                        pass ? Messages.VerdictOutcome.PASSED : Messages.VerdictOutcome.FAILED, reason, now);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("cannot serialise verdict", e);
            }
            metrics.counter("kyc.verdict.outcome", "outcome", pass ? "pass" : "fail").increment();
            log.info("event=review.verdict_released applicationId={} reviewId={} outcome={}", d.applicationId(),
                    d.reviewId(), pass ? "PASSED" : "FAILED");
        }
        return due.size();
    }

    public Optional<ReviewView> find(UUID applicationId) {
        return jdbc.query("SELECT * FROM review_record WHERE application_id = ?", (rs, n) -> {
            Timestamp due = rs.getTimestamp("verdict_due_at");
            Timestamp pub = rs.getTimestamp("verdict_published_at");
            return new ReviewView(rs.getObject("review_id", UUID.class), rs.getObject("application_id", UUID.class),
                    rs.getString("status"), rs.getString("reason"), due == null ? null : due.toInstant(),
                    pub == null ? null : pub.toInstant(), rs.getTimestamp("created_at").toInstant());
        }, applicationId).stream().findFirst();
    }
}
