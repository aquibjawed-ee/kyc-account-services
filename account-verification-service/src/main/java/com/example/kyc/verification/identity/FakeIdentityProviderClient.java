package com.example.kyc.verification.identity;

import com.example.kyc.verification.messaging.Messages.PersonIdentity;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * In-process stand-in for the provider, active only under the compose profile. Matches every PAN except
 * the reserved test PAN, so both the pass path and the mismatch path can be exercised by hand.
 */
@Component
@Profile("compose")
public class FakeIdentityProviderClient implements IdentityProviderClient {

    public static final String RESERVED_MISMATCH_PAN = "ZZZZZ9999Z";

    @Override
    public IdentityResult verify(PersonIdentity p) {
        if (RESERVED_MISMATCH_PAN.equals(p.pan())) {
            return new IdentityResult(p.role(), false, "Reserved test PAN: simulated mismatch");
        }
        return new IdentityResult(p.role(), true, "match");
    }
}
