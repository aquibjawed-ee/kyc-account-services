package com.example.kyc.verification.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.kyc.verification.identity.IdentityProviderClient.IdentityResult;
import com.example.kyc.verification.messaging.Messages.PersonIdentity;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class IdentityCheckCoordinatorTest {

    private static final List<PersonIdentity> PEOPLE = List.of(
            new PersonIdentity("PRIMARY", "A", "AAAAA1111A", "234567890124"),
            new PersonIdentity("NOMINEE", "B", "BBBBB2222B", "234567890124"),
            new PersonIdentity("GUARANTOR", "C", "CCCCC3333C", "234567890124"));

    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final List<ThreadPoolExecutor> pools = new java.util.ArrayList<>();
    private ThreadPoolExecutor pool;

    private ThreadPoolExecutor pool(int size) {
        pool = new ThreadPoolExecutor(size, size, 0, TimeUnit.SECONDS, new SynchronousQueue<>(),
                r -> new Thread(r, "identity-check-test"), new ThreadPoolExecutor.AbortPolicy());
        pools.add(pool);
        return pool;
    }

    @AfterEach
    void shutdown() {
        pools.forEach(ThreadPoolExecutor::shutdownNow);
    }

    @Test
    void allMatchRunsOnPoolThreadsInParallelNotOnTheCaller() {
        Set<String> threads = ConcurrentHashMap.newKeySet();
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        IdentityProviderClient client = p -> {
            threads.add(Thread.currentThread().getName());
            maxConcurrent.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            concurrent.decrementAndGet();
            return new IdentityResult(p.role(), true, "match");
        };
        var coordinator = new IdentityCheckCoordinator(client, pool(3), Duration.ofSeconds(2), metrics);

        List<IdentityResult> results = coordinator.verifyAll(PEOPLE);

        assertThat(results).hasSize(3).allMatch(IdentityResult::match);
        assertThat(threads).containsOnly("identity-check-test");
        assertThat(maxConcurrent.get()).isGreaterThan(1); // parallel, not three sequential calls
    }

    @Test
    void threeCallsAt60PercentOfDeadlineSucceedOnlyBecauseTheyRunInParallel() {
        IdentityProviderClient client = p -> {
            try {
                Thread.sleep(600);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new IdentityResult(p.role(), true, "match");
        };
        var coordinator = new IdentityCheckCoordinator(client, pool(3), Duration.ofSeconds(1), metrics);
        assertThat(coordinator.verifyAll(PEOPLE)).hasSize(3); // sequential would take 1.8s > 1s deadline
    }

    @Test
    void deadlineExpiryCancelsOutstandingChecksAndRaisesProviderUnavailable() throws Exception {
        CountDownLatch never = new CountDownLatch(1);
        AtomicInteger interrupted = new AtomicInteger();
        IdentityProviderClient client = p -> {
            try {
                never.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                interrupted.incrementAndGet(); // cancel(true) must really interrupt the running check
                Thread.currentThread().interrupt();
            }
            return new IdentityResult(p.role(), true, "match");
        };
        ThreadPoolExecutor pool = pool(3);
        var coordinator = new IdentityCheckCoordinator(client, pool, Duration.ofMillis(200), metrics);

        long start = System.nanoTime();
        assertThatThrownBy(() -> coordinator.verifyAll(PEOPLE)).isInstanceOf(ProviderUnavailableException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2)); // waited once
        assertThat(metrics.counter("kyc.identity.abandoned", "reason", "deadline").count()).isEqualTo(1.0);

        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
            assertThat(interrupted.get()).isEqualTo(3); // all three abandoned checks were interrupted
            assertThat(pool.getActiveCount()).isZero(); // pool threads released
        });
        // the pool is immediately usable again: a follow-up call is not refused as pool_full
        var next = new IdentityCheckCoordinator(p -> new IdentityResult(p.role(), true, "match"), pool,
                Duration.ofSeconds(1), metrics);
        assertThat(next.verifyAll(PEOPLE)).hasSize(3);
    }

    @Test
    void fullPoolRaisesProviderUnavailableAndNeverRunsOnTheCallingThread() {
        Set<String> threads = ConcurrentHashMap.newKeySet();
        IdentityProviderClient client = p -> {
            threads.add(Thread.currentThread().getName());
            return new IdentityResult(p.role(), true, "match");
        };
        var coordinator = new IdentityCheckCoordinator(client, pool(2), Duration.ofSeconds(1), metrics); // 3rd refused

        // Hold both pool threads so the third submission is refused.
        CountDownLatch hold = new CountDownLatch(1);
        pool.execute(() -> await(hold));
        pool.execute(() -> await(hold));

        String caller = Thread.currentThread().getName();
        assertThatThrownBy(() -> coordinator.verifyAll(PEOPLE)).isInstanceOf(ProviderUnavailableException.class);
        hold.countDown();

        assertThat(threads).doesNotContain(caller);
        assertThat(metrics.counter("kyc.identity.abandoned", "reason", "pool_full").count()).isEqualTo(1.0);
    }

    @Test
    void providerFailureIsUnavailableButFourXxIsNonRetryable() {
        var failing = new IdentityCheckCoordinator(p -> {
            throw new ProviderUnavailableException("503");
        }, pool(3), Duration.ofSeconds(1), metrics);
        assertThatThrownBy(() -> failing.verifyAll(PEOPLE)).isInstanceOf(ProviderUnavailableException.class);

        var rejecting = new IdentityCheckCoordinator(p -> {
            throw new NonRetryableProviderException("400");
        }, pool(3), Duration.ofSeconds(1), metrics); // fresh pool: no leftover busy threads from the first call
        assertThatThrownBy(() -> rejecting.verifyAll(PEOPLE)).isInstanceOf(NonRetryableProviderException.class);
    }

    @Test
    void mismatchIsReportedNotThrown() {
        var coordinator = new IdentityCheckCoordinator(
                p -> new IdentityResult(p.role(), !"NOMINEE".equals(p.role()), "PAN mismatch"), pool(3),
                Duration.ofSeconds(1), metrics);
        assertThat(coordinator.verifyAll(PEOPLE)).filteredOn(r -> !r.match()).extracting(IdentityResult::role)
                .containsExactly("NOMINEE");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
