package com.example.kyc.creation.domain;

/**
 * Pure transition table for the KYC lifecycle (no I/O, trivially unit-testable).
 *
 * <p>Allowed: STARTED→IN_PROGRESS, IN_PROGRESS→MANUAL_REVIEW_PENDING,
 * {IN_PROGRESS, MANUAL_REVIEW_PENDING}→{KYC_DONE, REVIEW_FAILED}.
 * Agreed exceptions: an ack in a final state is ignored (expected race); an ack or verdict in STARTED
 * is applied as though IN_PROGRESS had already happened.
 */
public final class KycStateMachine {

    public enum Event { REQUEST_RELAYED, ACK, VERDICT_PASSED, VERDICT_FAILED }

    public enum Outcome { APPLIED, IGNORED, REJECTED }

    public record Result(Outcome outcome, KycStatus next) {}

    private KycStateMachine() {}

    public static Result apply(KycStatus current, Event event) {
        return switch (event) {
            case REQUEST_RELAYED -> current == KycStatus.STARTED
                    ? applied(KycStatus.IN_PROGRESS)
                    : ignored(current);
            case ACK -> switch (current) {
                case STARTED, IN_PROGRESS -> applied(KycStatus.MANUAL_REVIEW_PENDING);
                case KYC_DONE, REVIEW_FAILED -> ignored(current);
                case MANUAL_REVIEW_PENDING -> rejected(current);
            };
            case VERDICT_PASSED -> verdict(current, KycStatus.KYC_DONE);
            case VERDICT_FAILED -> verdict(current, KycStatus.REVIEW_FAILED);
        };
    }

    private static Result verdict(KycStatus current, KycStatus target) {
        return current.isFinal() ? rejected(current) : applied(target);
    }

    private static Result applied(KycStatus next) { return new Result(Outcome.APPLIED, next); }

    private static Result ignored(KycStatus current) { return new Result(Outcome.IGNORED, current); }

    private static Result rejected(KycStatus current) { return new Result(Outcome.REJECTED, current); }
}
