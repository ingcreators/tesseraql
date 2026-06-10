package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.operations.batch.JobRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration test for cluster-wide scheduled-firing claims (design ch. 26): when several runtime
 * nodes fire the same schedule, exactly one wins the {@code (job_id, fire_time)} claim and runs
 * the job; the others skip.
 */
@Testcontainers
class JobClaimIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private JobRepository repository() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        JobRepository repository = new JobRepository(dataSource);
        repository.ensureSchema();
        return repository;
    }

    @Test
    void onlyOneNodeClaimsEachFiring() throws Exception {
        JobRepository repository = repository();
        Instant fireTime = Instant.parse("2026-06-10T02:00:00Z");

        assertThat(repository.tryClaimFiring("nightly", fireTime)).isTrue();
        // A second node firing at the same scheduled time loses the claim.
        assertThat(repository.tryClaimFiring("nightly", fireTime)).isFalse();
        // Different firing or different job claims independently.
        assertThat(repository.tryClaimFiring("nightly", fireTime.plusSeconds(60))).isTrue();
        assertThat(repository.tryClaimFiring("weekly", fireTime)).isTrue();
    }

    @Test
    void concurrentClaimsYieldExactlyOneWinner() throws Exception {
        JobRepository repository = repository();
        Instant fireTime = Instant.parse("2026-06-11T02:00:00Z");

        List<Callable<Boolean>> nodes = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            nodes.add(() -> repository.tryClaimFiring("nightly", fireTime));
        }
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            int winners = 0;
            for (Future<Boolean> result : pool.invokeAll(nodes)) {
                if (result.get()) {
                    winners++;
                }
            }
            assertThat(winners).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
