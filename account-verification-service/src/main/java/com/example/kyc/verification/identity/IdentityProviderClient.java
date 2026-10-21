package com.example.kyc.verification.identity;

import com.example.kyc.verification.messaging.Messages.PersonIdentity;

/** One verify call per person. Implementations must be thread-safe and bound their own call time. */
public interface IdentityProviderClient {

    /** @throws ProviderUnavailableException on 5xx / timeout / connection failure (retryable)
     *  @throws NonRetryableProviderException on 4xx (goes straight to the dead-letter topic) */
    IdentityResult verify(PersonIdentity person);

    record IdentityResult(String role, boolean match, String reason) {}
}
