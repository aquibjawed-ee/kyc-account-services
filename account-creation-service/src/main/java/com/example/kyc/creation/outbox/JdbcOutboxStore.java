package com.example.kyc.creation.outbox;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.time.Duration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcOutboxStore implements OutboxStore {

    private final JdbcTemplate jdbc;

    public JdbcOutboxStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void enqueue(UUID aggregateId, String topic, String messageType, String payload) {
        jdbc.update("""
                INSERT INTO outbox(id, aggregate_id, topic, message_type, payload, created_at)
                VALUES (?,?,?,?,?::jsonb, now())""", UUID.randomUUID(), aggregateId, topic, messageType, payload);
    }

    @Override
    @Transactional
    public List<OutboxRow> claim(int batch, Duration lease) {
        List<OutboxRow> rows = jdbc.query("""
                UPDATE outbox SET claimed_until = now() + (?::double precision * interval '1 millisecond')
                WHERE id IN (SELECT id FROM outbox
                             WHERE published_at IS NULL AND (claimed_until IS NULL OR claimed_until < now())
                             ORDER BY seq LIMIT ? FOR UPDATE SKIP LOCKED)
                RETURNING id, aggregate_id, topic, message_type, payload::text AS payload, seq""",
                (rs, n) -> new OutboxRow(rs.getObject("id", UUID.class), rs.getObject("aggregate_id", UUID.class),
                        rs.getString("topic"), rs.getString("message_type"), rs.getString("payload"),
                        rs.getLong("seq")),
                lease.toMillis(), batch);
        return rows.stream().sorted(Comparator.comparingLong(OutboxRow::seq)).toList();
    }

    @Override
    @Transactional
    public void markPublished(List<OutboxRow> rows) {
        for (OutboxRow r : rows) {
            jdbc.update("UPDATE outbox SET published_at = now() WHERE id = ?", r.id());
            if (KYC_REQUEST.equals(r.messageType())) {
                // Conditional: a no-op when an ack/verdict already moved the application on (STARTED race).
                jdbc.update("""
                        UPDATE kyc_details SET kyc_status = 'IN_PROGRESS', updated_at = now(), version = version + 1
                        WHERE application_id = ? AND kyc_status = 'STARTED'""", r.aggregateId());
            }
        }
    }

    @Override
    public double oldestUnsentAgeSeconds() {
        Double age = jdbc.queryForObject("""
                SELECT COALESCE(EXTRACT(EPOCH FROM (now() - min(created_at))), 0)::double precision
                FROM outbox WHERE published_at IS NULL""", Double.class);
        return age == null ? 0 : age;
    }
}
