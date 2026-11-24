package com.example.kyc.creation.web;

import com.example.kyc.creation.health.KafkaProducerHealthProbe;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Health", description = "Dependency health checks")
public class KafkaHealthController {

    private final KafkaProducerHealthProbe probe;

    public KafkaHealthController(KafkaProducerHealthProbe probe) {
        this.probe = probe;
    }

    @Operation(summary = "Kafka producer health",
            description = "Actively probes the brokers the Kafka producer is configured to use, bounded by "
                    + "kyc.health.kafka-timeout. Returns UP when at least one broker answers in time, "
                    + "otherwise DOWN.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Producer brokers reachable, status=UP"),
            @ApiResponse(responseCode = "503", description = "Producer brokers unreachable, status=DOWN")})
    @GetMapping("/health/kafka")
    public ResponseEntity<Map<String, String>> kafka() {
        if (probe.isUp()) {
            return ResponseEntity.ok(Map.of("status", "UP"));
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("status", "DOWN"));
    }
}
