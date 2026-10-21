package com.example.kyc.creation.repo;

import com.example.kyc.creation.domain.KycStatus;
import com.example.kyc.creation.domain.Role;
import com.example.kyc.creation.web.Dtos;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** All SQL for the application aggregate (user_details, kyc_details, user_relationship). */
@Repository
public class AccountRepository {

    public record KycRow(UUID applicationId, String primaryPan, KycStatus status, String reason, UUID reviewId,
                         Instant slaBreachDetectedAt, Instant createdAt, Instant updatedAt, Instant deletedAt,
                         long version) {}

    private final JdbcTemplate jdbc;

    public AccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Instant ts(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    private static KycRow mapKyc(ResultSet rs, int n) throws SQLException {
        return new KycRow(rs.getObject("application_id", UUID.class), rs.getString("primary_pan"),
                KycStatus.valueOf(rs.getString("kyc_status")), rs.getString("status_reason"),
                rs.getObject("review_id", UUID.class), ts(rs, "sla_breach_detected_at"), ts(rs, "created_at"),
                ts(rs, "updated_at"), ts(rs, "deleted_at"), rs.getLong("version"));
    }

    public void insertUser(UUID id, Dtos.PersonRequest p, Instant now) {
        jdbc.update("""
                INSERT INTO user_details(id, name, address, pan, aadhaar, parents_name, annual_income,
                                         income_source, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?)""",
                id, p.name(), p.address(), p.pan(), p.aadhaar(), p.parentsName(), p.annualIncome(),
                p.incomeSource(), Timestamp.from(now), Timestamp.from(now));
    }

    public void insertKyc(UUID applicationId, String primaryPan, Instant now) {
        jdbc.update("""
                INSERT INTO kyc_details(application_id, primary_pan, kyc_status, created_at, updated_at, version)
                VALUES (?,?,?,?,?,0)""",
                applicationId, primaryPan, KycStatus.STARTED.name(), Timestamp.from(now), Timestamp.from(now));
    }

    public void insertRelationship(UUID applicationId, UUID userId, Role role) {
        jdbc.update("INSERT INTO user_relationship(application_id, user_id, role) VALUES (?,?,?)",
                applicationId, userId, role.name());
    }

    public Optional<KycRow> findKyc(UUID applicationId) {
        return jdbc.query("SELECT * FROM kyc_details WHERE application_id = ?", AccountRepository::mapKyc,
                applicationId).stream().findFirst();
    }

    public Optional<UUID> findLiveIdByPrimaryPan(String pan) {
        return jdbc.query("SELECT application_id FROM kyc_details WHERE primary_pan = ? AND deleted_at IS NULL",
                (rs, n) -> rs.getObject(1, UUID.class), pan).stream().findFirst();
    }

    public Map<Role, Dtos.PersonResponse> loadPersons(UUID applicationId) {
        Map<Role, Dtos.PersonResponse> out = new EnumMap<>(Role.class);
        jdbc.query("""
                SELECT r.role, u.id, u.name, u.address, u.pan, u.aadhaar, u.parents_name, u.annual_income,
                       u.income_source
                FROM user_relationship r JOIN user_details u ON u.id = r.user_id
                WHERE r.application_id = ?""", (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
            out.put(Role.valueOf(rs.getString("role")), new Dtos.PersonResponse(
                    rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("address"),
                    rs.getString("pan"), rs.getString("aadhaar"), rs.getString("parents_name"),
                    rs.getBigDecimal("annual_income"), rs.getString("income_source")));
        }, applicationId);
        return out;
    }

    public List<UUID> listLiveIds(KycStatus status, Boolean slaBreached, int page, int size) {
        List<Object> args = new ArrayList<>();
        String where = where(status, slaBreached, args);
        args.add(size);
        args.add((long) page * size);
        return jdbc.query("SELECT application_id FROM kyc_details" + where
                        + " ORDER BY created_at DESC, application_id LIMIT ? OFFSET ?",
                (rs, n) -> rs.getObject(1, UUID.class), args.toArray());
    }

    public long countLive(KycStatus status, Boolean slaBreached) {
        List<Object> args = new ArrayList<>();
        String where = where(status, slaBreached, args);
        Long n = jdbc.queryForObject("SELECT count(*) FROM kyc_details" + where, Long.class, args.toArray());
        return n == null ? 0 : n;
    }

    private static String where(KycStatus status, Boolean slaBreached, List<Object> args) {
        StringBuilder w = new StringBuilder(" WHERE deleted_at IS NULL");
        if (status != null) {
            w.append(" AND kyc_status = ?");
            args.add(status.name());
        }
        if (slaBreached != null) {
            w.append(slaBreached ? " AND sla_breach_detected_at IS NOT NULL" : " AND sla_breach_detected_at IS NULL");
        }
        return w.toString();
    }

    public void updatePerson(UUID applicationId, Role role, Dtos.PersonPatch p) {
        jdbc.update("""
                UPDATE user_details u SET address = COALESCE(?, u.address),
                       parents_name = COALESCE(?, u.parents_name),
                       annual_income = COALESCE(?, u.annual_income),
                       income_source = COALESCE(?, u.income_source),
                       updated_at = now()
                FROM user_relationship r
                WHERE r.user_id = u.id AND r.application_id = ? AND r.role = ?""",
                p.address(), p.parentsName(), p.annualIncome(), p.incomeSource(), applicationId, role.name());
    }

    /** Optimistic lock: returns 0 when the version moved or the application was deleted. */
    public int bumpVersion(UUID applicationId, long expectedVersion) {
        return jdbc.update("""
                UPDATE kyc_details SET version = version + 1, updated_at = now()
                WHERE application_id = ? AND version = ? AND deleted_at IS NULL""", applicationId, expectedVersion);
    }

    public int softDelete(UUID applicationId) {
        return jdbc.update("""
                UPDATE kyc_details SET deleted_at = now(), updated_at = now(), version = version + 1
                WHERE application_id = ? AND deleted_at IS NULL""", applicationId);
    }

    /** Optimistic KYC transition; returns 0 when another writer won. */
    public int applyTransition(UUID applicationId, long expectedVersion, KycStatus next, String reason, UUID reviewId) {
        return jdbc.update("""
                UPDATE kyc_details SET kyc_status = ?, status_reason = COALESCE(?, status_reason),
                       review_id = COALESCE(?, review_id), updated_at = now(), version = version + 1
                WHERE application_id = ? AND version = ?""",
                next.name(), reason, reviewId, applicationId, expectedVersion);
    }

    /** Conditional breach marker; returns the applications newly flagged. */
    public List<KycRow> flagSlaBreaches(long windowMillis) {
        return jdbc.query("""
                UPDATE kyc_details SET sla_breach_detected_at = now(), updated_at = now(), version = version + 1
                WHERE deleted_at IS NULL AND sla_breach_detected_at IS NULL
                  AND kyc_status NOT IN ('KYC_DONE','REVIEW_FAILED')
                  AND created_at < now() - (?::double precision * interval '1 millisecond')
                RETURNING *""", AccountRepository::mapKyc, windowMillis);
    }
}
