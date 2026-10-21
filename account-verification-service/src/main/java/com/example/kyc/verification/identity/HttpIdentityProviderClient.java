package com.example.kyc.verification.identity;

import com.example.kyc.verification.config.KycProperties;
import com.example.kyc.verification.messaging.Messages.PersonIdentity;
import java.net.http.HttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Real HTTP client for the external PAN/Aadhaar provider (WireMock in tests). Not used under the compose profile.
 * Uses the JDK HttpClient because, unlike HttpURLConnection, a blocked call can be interrupted, so an abandoned
 * check releases its pool thread immediately.
 */
@Component
@Profile("!compose")
public class HttpIdentityProviderClient implements IdentityProviderClient {

    private static final Logger log = LoggerFactory.getLogger(HttpIdentityProviderClient.class);

    public record VerifyRequest(String name, String pan, String aadhaar) {}

    public record VerifyResponse(Boolean match, String reason) {}

    private final RestClient client;

    public HttpIdentityProviderClient(KycProperties props) {
        KycProperties.IdentityProvider cfg = props.identityProvider();
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(cfg.connectTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(cfg.readTimeout()); // per-call bound on pool-thread time
        this.client = RestClient.builder().baseUrl(cfg.baseUrl()).requestFactory(factory).build();
    }

    @Override
    public IdentityResult verify(PersonIdentity p) {
        try {
            VerifyResponse r = client.post().uri("/identity/v1/verify")
                    .body(new VerifyRequest(p.name(), p.pan(), p.aadhaar()))
                    .retrieve().body(VerifyResponse.class);
            if (r == null || r.match() == null) {
                throw new ProviderUnavailableException("identity provider returned an empty/invalid body");
            }
            return new IdentityResult(p.role(), r.match(), r.reason());
        } catch (HttpServerErrorException e) {
            throw new ProviderUnavailableException("identity provider 5xx: " + e.getStatusCode(), e);
        } catch (HttpClientErrorException e) {
            throw new NonRetryableProviderException("identity provider rejected the request: " + e.getStatusCode());
        } catch (ResourceAccessException e) { // connect/read timeout, interrupt, connection reset
            log.warn("event=identity.call_failed role={} cause={}", p.role(), e.getMessage());
            throw new ProviderUnavailableException("identity provider unreachable, timed out or interrupted", e);
        }
    }
}
