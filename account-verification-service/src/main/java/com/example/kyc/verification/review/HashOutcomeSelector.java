package com.example.kyc.verification.review;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Deterministic: bucket = first 4 bytes of SHA-256(applicationId) as unsigned int, mod 1000.
 * 0-49 NO_REPLY (5%), 50-144 FAIL (9.5%), 145-999 PASS (85.5%) → pass:fail = 9:1.
 */
@Component
public class HashOutcomeSelector implements OutcomeSelector {

    public static int bucket(UUID applicationId) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256")
                    .digest(applicationId.toString().getBytes(StandardCharsets.UTF_8));
            long unsigned = ((h[0] & 0xFFL) << 24) | ((h[1] & 0xFFL) << 16) | ((h[2] & 0xFFL) << 8) | (h[3] & 0xFFL);
            return (int) (unsigned % 1000);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public PlannedOutcome select(UUID applicationId) {
        int b = bucket(applicationId);
        if (b < 50) return PlannedOutcome.NO_REPLY;
        if (b < 145) return PlannedOutcome.FAIL;
        return PlannedOutcome.PASS;
    }
}
