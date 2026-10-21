package com.example.kyc.verification.identity;

/** Provider 5xx, timeout, overall-deadline expiry, or a full identity-check pool. Retried via delayed retry topics. */
public class ProviderUnavailableException extends RuntimeException {

    public ProviderUnavailableException(String message) {
        super(message);
    }

    public ProviderUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
