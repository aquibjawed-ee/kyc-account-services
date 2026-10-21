package com.example.kyc.verification.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.example.kyc.verification.review.OutcomeSelector.PlannedOutcome;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HashOutcomeSelectorTest {

    private final HashOutcomeSelector selector = new HashOutcomeSelector();

    @Test
    void distributionOver100kIdsIsAboutFiveNineAndAHalfAndEightyFiveAndAHalfPercent() {
        Map<PlannedOutcome, Integer> counts = new EnumMap<>(PlannedOutcome.class);
        int n = 100_000;
        for (int i = 0; i < n; i++) {
            counts.merge(selector.select(UUID.randomUUID()), 1, Integer::sum);
        }
        assertThat(counts.get(PlannedOutcome.NO_REPLY) * 100.0 / n).isCloseTo(5.0, within(1.0));
        assertThat(counts.get(PlannedOutcome.FAIL) * 100.0 / n).isCloseTo(9.5, within(1.0));
        assertThat(counts.get(PlannedOutcome.PASS) * 100.0 / n).isCloseTo(85.5, within(1.0));
        // the brief's ~9:1 pass:fail ratio among messages that get a reply
        double ratio = (double) counts.get(PlannedOutcome.PASS) / counts.get(PlannedOutcome.FAIL);
        assertThat(ratio).isBetween(8.0, 10.0);
    }

    @Test
    void selectionIsDeterministicPerApplicationId() {
        UUID id = UUID.randomUUID();
        assertThat(selector.select(id)).isEqualTo(selector.select(id));
        assertThat(HashOutcomeSelector.bucket(id)).isEqualTo(HashOutcomeSelector.bucket(id)).isBetween(0, 999);
    }
}
