package com.example.kyc.verification.web;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Describes the read-only HTTP surface for Swagger UI. This service's real work arrives over Kafka,
 * described in contracts/kyc-messages-asyncapi.yaml.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI accountVerificationOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Account Verification Service")
                        .version("0.1.0")
                        .description("""
                                Adjudicates KYC for applications raised by the account creation service. It is \
                                driven by Kafka, not by HTTP: the only HTTP endpoint is a read-only lookup so \
                                testers can inspect the verification side directly.

                                **Flow:** consume a KYC request -> check all three identities against the \
                                external PAN/Aadhaar provider, in parallel on a bounded pool under one overall \
                                deadline and never on the Kafka listener thread -> persist a review at \
                                `MANUAL_REVIEW_STARTED` with a stored `verdictDueAt` -> acknowledge -> release \
                                a verdict when it falls due.

                                **Simulated manual review.** The outcome is chosen deterministically by \
                                hashing the application id: roughly 9 pass to 1 fail, plus a fixed subset that \
                                deliberately never gets a verdict at all. Those silent ones are what the \
                                creation service's SLA sweep later surfaces.

                                **Identity mismatch short-circuits.** A mismatch fails the application \
                                immediately with the offending role and reason, skipping the simulated review. \
                                A provider outage retries with backoff and then dead-letters.""")
                        .license(new License().name("Internal build exercise")))
                .servers(List.of(new Server().url("http://localhost:8082").description("Local compose stack")));
    }
}
