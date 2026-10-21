package com.example.kyc.creation.service;

import com.example.kyc.creation.config.KycProperties;
import com.example.kyc.creation.repo.AccountRepository;
import com.example.kyc.creation.repo.AccountRepository.KycRow;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Flags applications with no final verdict inside the SLA window. DB-only work; runs on the scheduler pool. */
@Component
public class SlaSweeper {

    private static final Logger log = LoggerFactory.getLogger(SlaSweeper.class);

    private final AccountRepository repo;
    private final KycProperties props;
    private final MeterRegistry metrics;

    public SlaSweeper(AccountRepository repo, KycProperties props, MeterRegistry metrics) {
        this.repo = repo;
        this.props = props;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${kyc.sla.sweep-interval}")
    public void sweep() {
        try {
            List<KycRow> breached = repo.flagSlaBreaches(props.sla().window().toMillis());
            for (KycRow row : breached) {
                log.warn("KYC SLA breached applicationId={} status={} createdAt={}", row.applicationId(),
                        row.status(), row.createdAt());
                metrics.counter("kyc.sla.breached").increment();
            }
        } catch (RuntimeException e) {
            log.error("event=sla.sweep_failed", e);
        }
    }
}
