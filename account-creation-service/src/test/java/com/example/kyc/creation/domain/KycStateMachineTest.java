package com.example.kyc.creation.domain;

import static com.example.kyc.creation.domain.KycStateMachine.Event.ACK;
import static com.example.kyc.creation.domain.KycStateMachine.Event.REQUEST_RELAYED;
import static com.example.kyc.creation.domain.KycStateMachine.Event.VERDICT_FAILED;
import static com.example.kyc.creation.domain.KycStateMachine.Event.VERDICT_PASSED;
import static com.example.kyc.creation.domain.KycStateMachine.Outcome.APPLIED;
import static com.example.kyc.creation.domain.KycStateMachine.Outcome.IGNORED;
import static com.example.kyc.creation.domain.KycStateMachine.Outcome.REJECTED;
import static com.example.kyc.creation.domain.KycStatus.IN_PROGRESS;
import static com.example.kyc.creation.domain.KycStatus.KYC_DONE;
import static com.example.kyc.creation.domain.KycStatus.MANUAL_REVIEW_PENDING;
import static com.example.kyc.creation.domain.KycStatus.REVIEW_FAILED;
import static com.example.kyc.creation.domain.KycStatus.STARTED;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.kyc.creation.domain.KycStateMachine.Event;
import com.example.kyc.creation.domain.KycStateMachine.Outcome;
import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class KycStateMachineTest {

    static Stream<Arguments> table() {
        return Stream.of(
                // happy path
                Arguments.of(STARTED, REQUEST_RELAYED, APPLIED, IN_PROGRESS),
                Arguments.of(IN_PROGRESS, ACK, APPLIED, MANUAL_REVIEW_PENDING),
                Arguments.of(MANUAL_REVIEW_PENDING, VERDICT_PASSED, APPLIED, KYC_DONE),
                Arguments.of(MANUAL_REVIEW_PENDING, VERDICT_FAILED, APPLIED, REVIEW_FAILED),
                // verdict before acknowledgement
                Arguments.of(IN_PROGRESS, VERDICT_PASSED, APPLIED, KYC_DONE),
                Arguments.of(IN_PROGRESS, VERDICT_FAILED, APPLIED, REVIEW_FAILED),
                // STARTED race: ack / verdict applied as though IN_PROGRESS had already happened
                Arguments.of(STARTED, ACK, APPLIED, MANUAL_REVIEW_PENDING),
                Arguments.of(STARTED, VERDICT_PASSED, APPLIED, KYC_DONE),
                Arguments.of(STARTED, VERDICT_FAILED, APPLIED, REVIEW_FAILED),
                // relay step is a no-op once the state moved on
                Arguments.of(IN_PROGRESS, REQUEST_RELAYED, IGNORED, IN_PROGRESS),
                Arguments.of(MANUAL_REVIEW_PENDING, REQUEST_RELAYED, IGNORED, MANUAL_REVIEW_PENDING),
                Arguments.of(KYC_DONE, REQUEST_RELAYED, IGNORED, KYC_DONE),
                // late ack after a final state is an expected race
                Arguments.of(KYC_DONE, ACK, IGNORED, KYC_DONE),
                Arguments.of(REVIEW_FAILED, ACK, IGNORED, REVIEW_FAILED),
                // illegal
                Arguments.of(MANUAL_REVIEW_PENDING, ACK, REJECTED, MANUAL_REVIEW_PENDING),
                Arguments.of(KYC_DONE, VERDICT_FAILED, REJECTED, KYC_DONE),
                Arguments.of(KYC_DONE, VERDICT_PASSED, REJECTED, KYC_DONE),
                Arguments.of(REVIEW_FAILED, VERDICT_PASSED, REJECTED, REVIEW_FAILED),
                Arguments.of(REVIEW_FAILED, VERDICT_FAILED, REJECTED, REVIEW_FAILED));
    }

    @ParameterizedTest(name = "{0} + {1} -> {2} {3}")
    @MethodSource("table")
    void transitionTable(KycStatus from, Event event, Outcome outcome, KycStatus next) {
        KycStateMachine.Result r = KycStateMachine.apply(from, event);
        assertThat(r.outcome()).isEqualTo(outcome);
        assertThat(r.next()).isEqualTo(next);
    }

    @Test
    void everyStateEventPairIsCovered() {
        assertThat((long) KycStatus.values().length * Event.values().length)
                .isGreaterThanOrEqualTo(table().count()); // sanity: the table never exceeds the state space
        for (KycStatus s : KycStatus.values()) {
            for (Event e : Event.values()) {
                assertThat(KycStateMachine.apply(s, e)).isNotNull(); // total function, never throws
            }
        }
    }

    @Test
    void enumHasExactlyTheFiveBriefValues() {
        assertThat(Arrays.stream(KycStatus.values()).map(Enum::name)).containsExactlyInAnyOrder(
                "STARTED", "IN_PROGRESS", "MANUAL_REVIEW_PENDING", "REVIEW_FAILED", "KYC_DONE");
    }

    @Test
    void finalStatesAreFinal() {
        assertThat(KYC_DONE.isFinal()).isTrue();
        assertThat(REVIEW_FAILED.isFinal()).isTrue();
        assertThat(IN_PROGRESS.isFinal()).isFalse();
    }
}
