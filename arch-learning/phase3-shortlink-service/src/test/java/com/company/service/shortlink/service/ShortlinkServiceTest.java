package com.company.service.shortlink.service;

import com.company.common.core.BizException;
import com.company.common.core.ErrorCode;
import com.company.service.shortlink.domain.Base62;
import com.company.service.shortlink.domain.SegmentIdGenerator;
import com.company.service.shortlink.domain.Shortlink;
import com.company.service.shortlink.infra.InMemorySegmentSource;
import com.company.service.shortlink.infra.InMemoryShortlinkRepository;
import com.company.service.shortlink.infra.MemoryBloomFilter;
import com.company.service.shortlink.infra.MemoryClickStore;
import com.company.service.shortlink.infra.MemoryRemoteCache;
import com.company.service.shortlink.infra.ShortlinkCache;
import com.company.service.shortlink.support.ShortlinkMetrics;
import com.company.service.shortlink.support.ShortlinkProperties;
import com.company.starter.lock.InMemoryDistributedLock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShortlinkServiceTest {

    private InMemoryShortlinkRepository repository;

    private MemoryBloomFilter bloomFilter;

    private ShortlinkCache cache;

    private MemoryClickStore clickStore;

    private ClickRecorder clickRecorder;

    private ShortlinkMetrics metrics;

    private ShortlinkProperties properties;

    private ShortlinkService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryShortlinkRepository();
        bloomFilter = new MemoryBloomFilter(10_000, 0.0001);
        cache = new ShortlinkCache(new MemoryRemoteCache(),
                1_000, Duration.ofMinutes(10), Duration.ofSeconds(30),
                Duration.ofMinutes(30), Duration.ofMinutes(2), 0.1);
        clickStore = new MemoryClickStore();
        clickRecorder = new ClickRecorder(clickStore);
        metrics = new ShortlinkMetrics();
        properties = new ShortlinkProperties();
        properties.setDomain("http://s.test/shortlink");
        properties.getCache().setLockWait(Duration.ofSeconds(2));
        properties.getCache().setLockLease(Duration.ofSeconds(5));
        service = new ShortlinkService(repository,
                new SegmentIdGenerator(new InMemorySegmentSource(), 1_000, 0.2, Runnable::run),
                bloomFilter, cache, new InMemoryDistributedLock("test:lock:"),
                clickRecorder, metrics, properties);
    }

    @Test
    void createThenResolveShouldHitLocalCacheOnly() {
        Shortlink link = service.create("https://example.com/a?b=1", null);

        assertThat(link.code()).hasSize(Base62.CODE_LENGTH);
        assertThat(Base62.decode(link.code())).isEqualTo(link.id());
        assertThat(service.shortUrl(link.code())).isEqualTo("http://s.test/shortlink/" + link.code());

        Optional<Shortlink> resolved = service.resolve(link.code());
        assertThat(resolved).contains(link);
        assertThat(repository.findCalls()).isZero();
        assertThat(cache.stats().l1Hits()).isEqualTo(1);
        assertThat(metrics.dbLoads()).isZero();
    }

    @Test
    void invalidUrlsShouldBeRejectedBeforeAllocation() {
        assertThatThrownBy(() -> service.create(null, null))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID);
        assertThatThrownBy(() -> service.create("example.com/x", null)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.create("ftp://example.com/x", null)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.create("http:///no-host", null)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.create("https://example.com/" + "x".repeat(2048), null))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.create("https://example.com", Instant.now().minusSeconds(1)))
                .isInstanceOf(BizException.class);
        // 任何一次非法请求都不应该消耗号段
        assertThat(repository.count()).isZero();
    }

    @Test
    void unknownCodeShouldBeRejectedByBloomWithoutTouchingStorage() {
        Optional<Shortlink> result = service.resolve("0000000");

        assertThat(result).isEmpty();
        assertThat(repository.findCalls()).isZero();
        assertThat(metrics.bloomRejects()).isEqualTo(1);
        assertThat(metrics.notFound()).isEqualTo(1);
    }

    @Test
    void malformedCodeShouldBeRejectedBeforeBloom() {
        assertThat(service.resolve("abc")).isEmpty();
        assertThat(service.resolve("00000-0")).isEmpty();
        assertThat(metrics.bloomRejects()).isZero();
        assertThat(repository.findCalls()).isZero();
    }

    @Test
    void negativeCacheShouldPreventRepeatedStorageReads() {
        String code = "000000A";
        bloomFilter.add(code);

        assertThat(service.resolve(code)).isEmpty();
        assertThat(service.resolve(code)).isEmpty();
        assertThat(service.resolve(code)).isEmpty();

        assertThat(repository.findCalls()).isEqualTo(1);
        assertThat(metrics.dbLoads()).isEqualTo(1);
    }

    @Test
    void singleFlightShouldCollapseConcurrentMissesIntoOneStorageRead() throws Exception {
        SlowRepository slowRepository = new SlowRepository(100);
        ShortlinkService singleFlightService = new ShortlinkService(slowRepository,
                new SegmentIdGenerator(new InMemorySegmentSource(), 1_000, 0.2, Runnable::run),
                bloomFilter, cache, new InMemoryDistributedLock("test:lock:"),
                clickRecorder, metrics, properties);
        String code = "000000B";
        bloomFilter.add(code);

        int threads = 16;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<Boolean> results = java.util.Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.execute(() -> {
                    try {
                        start.await();
                        results.add(singleFlightService.resolve(code).isEmpty());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(results).hasSize(threads).containsOnly(true);
        assertThat(slowRepository.findCalls()).as("并发未命中只允许回源一次").isEqualTo(1);
        assertThat(metrics.dbLoads()).isEqualTo(1);
        assertThat(metrics.singleFlightFallbacks()).isZero();
    }

    @Test
    void expiredLinkShouldBeTreatedAsMissingAndNegativelyCached() {
        Instant now = Instant.now();
        Shortlink expired = new Shortlink(42L, "000000C", "https://example.com/old",
                now.minusSeconds(3600), now.minusSeconds(60));
        repository.save(expired);
        bloomFilter.add(expired.code());

        assertThat(service.resolve(expired.code())).isEmpty();
        assertThat(repository.findCalls()).isEqualTo(1);
        // 第二次走负缓存，不再回源
        assertThat(service.resolve(expired.code())).isEmpty();
        assertThat(repository.findCalls()).isEqualTo(1);
    }

    @Test
    void deleteShouldRemoveLinkAndEvictCaches() {
        Shortlink link = service.create("https://example.com/to-delete", null);
        assertThat(service.resolve(link.code())).isPresent();

        assertThat(service.delete(link.code())).isTrue();
        assertThat(service.delete(link.code())).isFalse();

        // 布隆不支持删除，删除后仍会被放行；由缓存失效 + 存储兜底
        assertThat(service.resolve(link.code())).isEmpty();
        assertThat(repository.count()).isZero();
        assertThat(metrics.deleted()).isEqualTo(1);
    }

    @Test
    void redirectShouldRecordClicksAndFlushToStore() {
        Shortlink link = service.create("https://example.com/click", null);

        assertThat(service.resolveForRedirect(link.code())).isPresent();
        assertThat(service.resolveForRedirect(link.code())).isPresent();
        assertThat(clickRecorder.pending(link.code())).isEqualTo(2);
        assertThat(service.clicks(link.code())).isEqualTo(2);

        service.flushClicks();

        assertThat(clickStore.clicks(link.code())).isEqualTo(2);
        assertThat(clickRecorder.pending(link.code())).isZero();
        assertThat(service.clicks(link.code())).isEqualTo(2);
    }

    @Test
    void statsShouldExposeLayerCounters() {
        Shortlink link = service.create("https://example.com/stats", null);
        service.resolve(link.code());
        service.resolve("0000000");

        ShortlinkService.ShortlinkStats stats = service.stats();
        assertThat(stats.links()).isEqualTo(1);
        assertThat(stats.created()).isEqualTo(1);
        assertThat(stats.resolveRequests()).isEqualTo(2);
        assertThat(stats.resolved()).isEqualTo(1);
        assertThat(stats.notFound()).isEqualTo(1);
        assertThat(stats.bloomRejects()).isEqualTo(1);
        assertThat(stats.cacheL1Hits()).isEqualTo(1);
        assertThat(stats.remoteCache()).isEqualTo("memory");
        assertThat(stats.lockProvider()).isEqualTo("memory");
    }

    @Test
    void shortUrlShouldTolerateTrailingSlashInDomain() {
        properties.setDomain("http://s.test/shortlink/");
        assertThat(service.shortUrl("0000000")).isEqualTo("http://s.test/shortlink/0000000");
    }

    /**
     * 回源慢的存储：把锁内二次检查的收益放大到可观测。
     */
    private static final class SlowRepository extends InMemoryShortlinkRepository {

        private final long delayMillis;

        private SlowRepository(long delayMillis) {
            this.delayMillis = delayMillis;
        }

        @Override
        public Optional<Shortlink> findByCode(String code) {
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return super.findByCode(code);
        }
    }
}
