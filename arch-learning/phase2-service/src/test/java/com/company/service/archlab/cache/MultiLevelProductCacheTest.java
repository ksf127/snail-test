package com.company.service.archlab.cache;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MultiLevelProductCacheTest {

    private static final Duration TTL = Duration.ofMinutes(1);

    private final InMemoryRemoteCache remote = new InMemoryRemoteCache();

    @Test
    void shouldLoadDatabaseOnlyOnceWhenKeyIsHotButMissing() throws Exception {
        ProductRepository repository = new ProductRepository(50);
        MultiLevelProductCache cache = cache(repository, true, "single-flight:");

        int threads = 8;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Optional<ProductView>>> futures = new ArrayList<>();
        try {
            Callable<Optional<ProductView>> task = () -> {
                barrier.await();
                return cache.get(1L, TTL, MultiLevelProductCache.NullPolicy.DISABLED);
            };
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(task));
            }
            for (Future<Optional<ProductView>> future : futures) {
                assertThat(future.get()).isPresent();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(repository.loadCount()).isEqualTo(1);
    }

    @Test
    void shouldLetAnotherInstanceReadFromSharedL2() {
        ProductRepository first = new ProductRepository(0);
        ProductRepository second = new ProductRepository(0);
        MultiLevelProductCache writer = cache(first, true, "shared:");
        MultiLevelProductCache reader = cache(second, true, "shared:");

        assertThat(writer.get(1L, TTL, MultiLevelProductCache.NullPolicy.DISABLED)).isPresent();
        Optional<ProductView> fromL2 = reader.get(1L, TTL, MultiLevelProductCache.NullPolicy.DISABLED);

        assertThat(fromL2).isPresent();
        assertThat(fromL2.get().name()).isEqualTo("product-1");
        assertThat(second.loadCount()).isZero();
        assertThat(reader.counters().l2Hits()).isEqualTo(1);
    }

    @Test
    void shouldCacheEmptyResultWhenNullPolicyEnabled() {
        ProductRepository repository = new ProductRepository(0);
        MultiLevelProductCache cache = cache(repository, true, "null-cache:");

        assertThat(cache.get(999_999L, TTL, MultiLevelProductCache.NullPolicy.CACHE_NULL)).isEmpty();
        assertThat(cache.get(999_999L, TTL, MultiLevelProductCache.NullPolicy.CACHE_NULL)).isEmpty();

        assertThat(repository.loadCount()).isEqualTo(1);
        assertThat(cache.counters().nullHits()).isEqualTo(1);
    }

    @Test
    void shouldHitDatabaseEveryTimeWhenNullPolicyDisabled() {
        ProductRepository repository = new ProductRepository(0);
        MultiLevelProductCache cache = cache(repository, true, "null-disabled:");

        cache.get(999_999L, TTL, MultiLevelProductCache.NullPolicy.DISABLED);
        cache.get(999_999L, TTL, MultiLevelProductCache.NullPolicy.DISABLED);

        assertThat(repository.loadCount()).isEqualTo(2);
    }

    @Test
    void shouldFallBackToL2WhenL1Disabled() {
        ProductRepository repository = new ProductRepository(0);
        MultiLevelProductCache cache = cache(repository, false, "l1-off:");

        cache.get(1L, TTL, MultiLevelProductCache.NullPolicy.DISABLED);
        cache.get(1L, TTL, MultiLevelProductCache.NullPolicy.DISABLED);

        CacheCounters.Snapshot snapshot = cache.counters();
        assertThat(snapshot.l1Hits()).isZero();
        assertThat(snapshot.l2Hits()).isEqualTo(1);
        assertThat(snapshot.loads()).isEqualTo(1);
    }

    @Test
    void shouldInvalidateBothLevelsOnEvict() {
        ProductRepository repository = new ProductRepository(0);
        MultiLevelProductCache cache = cache(repository, true, "evict:");

        cache.get(1L, TTL, MultiLevelProductCache.NullPolicy.DISABLED);
        cache.evict(1L);

        assertThat(remote.get("evict:1")).isNull();
        assertThat(cache.get(1L, TTL, MultiLevelProductCache.NullPolicy.DISABLED)).isPresent();
        assertThat(repository.loadCount()).isEqualTo(2);
    }

    private MultiLevelProductCache cache(ProductRepository repository, boolean l1Enabled, String namespace) {
        return new MultiLevelProductCache(remote, repository, namespace, l1Enabled, 1024, TTL, 0);
    }
}
