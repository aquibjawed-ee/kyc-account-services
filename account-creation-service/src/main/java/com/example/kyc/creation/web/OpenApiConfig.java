package com.example.kyc.creation.web;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Describes the HTTP surface for Swagger UI. The Kafka surface is not an HTTP API and is described
 * separately in contracts/kyc-messages-asyncapi.yaml.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI accountCreationOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Account Creation Service")
                        .version("0.1.0")
                        .description("""
                                Captures an account application for a primary applicant, a nominee and a \
                                guarantor, then drives its KYC lifecycle asynchronously.

                                **Create never blocks on verification.** A POST persists the application at \
                                `STARTED` and returns immediately; the KYC request reaches Kafka through a \
                                transactional outbox, so no state change can commit without its message.

                                **KYC lifecycle:** `STARTED` -> `IN_PROGRESS` (request relayed) -> \
                                `MANUAL_REVIEW_PENDING` (acknowledged by the verification service) -> \
                                `KYC_DONE` or `REVIEW_FAILED` (verdict received). A verdict arriving before \
                                the acknowledgement is applied directly from `IN_PROGRESS`.

                                **SLA:** an application with no verdict inside the SLA window is flagged by a \
                                scheduled sweep. The breach is recorded as `slaBreachDetectedAt` alongside the \
                                state, never as a sixth enum value, and a late verdict is still applied.

                                **Update is restricted.** Identity fields (name, PAN, Aadhaar) are immutable \
                                because they are what gets verified; address, income, income source and \
                                parents' name remain editable. Delete is a soft delete.""")
                        .license(new License().name("Internal build exercise")))
                .servers(List.of(new Server().url("http://localhost:8081").description("Local compose stack")));
    }
}
