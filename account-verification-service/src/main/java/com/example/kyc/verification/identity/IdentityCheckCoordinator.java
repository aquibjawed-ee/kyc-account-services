package com.example.kyc.verification.identity;

import com.example.kyc.verification.config.KycProperties;
import com.example.kyc.verification.identity.IdentityProviderClient.IdentityResult;
import com.example.kyc.verification.messaging.Messages.PersonIdentity;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * Runs the per-person provider checks in parallel on the bounded identity-check pool and waits against ONE
 * overall deadline. Deadline expiry, provider failure or a full pool all mean "provider unavailable"; the
 * check never runs on the calling (listener) thread. Abandoned checks are cancelled with an interrupt
 * ({@code FutureTask.cancel(true)}) so their pool threads are released promptly.
 */
@Component
public class IdentityCheckCoordinator {

    private static final Logger log = LoggerFactory.getLogger(IdentityCheckCoordinator.class);

    private final IdentityProviderClient client;
    private final Executor executor;
    private final Duration deadline;
    private final MeterRegistry metrics;

    @Autowired
    public IdentityCheckCoordinator(IdentityProviderClient client,
                                    @Qualifier("identityCheckExecutor") ThreadPoolTaskExecutor executor,
                                    KycProperties props, MeterRegistry metrics) {
        this(client, executor.getThreadPoolExecutor(), props.identityProvider().overallDeadline(), metrics);
    }

    /** Test seam: any Executor whose rejection is a RejectedExecutionException. */
    public IdentityCheckCoordinator(IdentityProviderClient client, Executor executor, Duration deadline,
                                    MeterRegistry metrics) {
        this.client = client;
        this.executor = executor;
        this.deadline = deadline;
        this.metrics = metrics;
    }

    public List<IdentityResult> verifyAll(List<PersonIdentity> persons) {
        long deadlineAt = System.nanoTime() + deadline.toNanos();
        List<FutureTask<IdentityResult>> tasks = new ArrayList<>();
        try {
            for (PersonIdentity p : persons) {
                FutureTask<IdentityResult> task = new FutureTask<>(() -> timedVerify(p));
                executor.execute(task);
                tasks.add(task);
            }
        } catch (RejectedExecutionException e) {
            cancel(tasks);
            metrics.counter("kyc.identity.abandoned", "reason", "pool_full").increment();
            log.warn("event=identity.pool_full submitted={} total={}", tasks.size(), persons.size());
            throw new ProviderUnavailableException("identity-check pool is full", e);
        }
        List<IdentityResult> results = new ArrayList<>();
        try {
            for (FutureTask<IdentityResult> task : tasks) {
                long remaining = Math.max(0, deadlineAt - System.nanoTime());
                results.add(task.get(remaining, TimeUnit.NANOSECONDS)); // shared deadline across all three
            }
        } catch (TimeoutException e) {
            cancel(tasks);
            metrics.counter("kyc.identity.abandoned", "reason", "deadline").increment();
            log.warn("event=identity.deadline_exceeded deadlineMs={}", deadline.toMillis());
            throw new ProviderUnavailableException("identity checks exceeded the overall deadline", e);
        } catch (ExecutionException e) {
            cancel(tasks);
            Throwable cause = e.getCause();
            if (cause instanceof NonRetryableProviderException nr) throw nr;
            if (cause instanceof ProviderUnavailableException pu) throw pu;
            throw new ProviderUnavailableException("identity check failed", cause);
        } catch (CancellationException e) {
            cancel(tasks);
            throw new ProviderUnavailableException("identity check cancelled", e);
        } catch (InterruptedException e) {
            cancel(tasks);
            Thread.currentThread().interrupt();
            throw new ProviderUnavailableException("interrupted while waiting for identity checks", e);
        }
        return results;
    }

    private IdentityResult timedVerify(PersonIdentity p) {
        Timer.Sample sample = Timer.start(metrics);
        String result = "error";
        try {
            IdentityResult r = client.verify(p);
            result = r.match() ? "match" : "mismatch";
            return r;
        } catch (ProviderUnavailableException e) {
            result = isTimeout(e) ? "timeout" : "unavailable";
            throw e;
        } finally {
            sample.stop(metrics.timer("kyc.identity.call", "result", result));
        }
    }

    private static boolean isTimeout(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof HttpTimeoutException || c instanceof SocketTimeoutException) return true;
        }
        return false;
    }

    private static void cancel(List<FutureTask<IdentityResult>> tasks) {
        tasks.forEach(t -> t.cancel(true)); // interrupts the running check
    }
}
