package com.example.kyc.verification.identity;

/** Provider 4xx or an unparseable request: retrying cannot help, so it goes straight to the dead-letter topic. */
public class NonRetryableProviderException extends RuntimeException {

    public NonRetryableProviderException(String message) {
        super(message);
    }

    public NonRetryableProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
