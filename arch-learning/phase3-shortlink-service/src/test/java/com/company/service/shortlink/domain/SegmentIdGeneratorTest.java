package com.company.service.shortlink.domain;

import com.company.service.shortlink.infra.InMemorySegmentSource;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SegmentIdGeneratorTest {

    @Test
    void sequentialIdsShouldBeUniqueAndContiguous() {
        SegmentIdGenerator generator = new SegmentIdGenerator(new InMemorySegmentSource(), 1000, 0.2, Runnable::run);
        for (long expected = 1; expected <= 10_000; expected++) {
            assertThat(generator.nextId()).isEqualTo(expected);
        }
        // 10 段号码，加上末尾可能多预取的一段
        assertThat(generator.sourceCalls()).isBetween(10L, 12L);
        assertThat(generator.prefetchedSegments()).isGreaterThanOrEqualTo(9);
    }

    @Test
    void prefetchShouldBeDisabledWhenThresholdIsZero() {
        SegmentIdGenerator generator = new SegmentIdGenerator(new InMemorySegmentSource(), 10, 0, Runnable::run);
        for (int i = 0; i < 100; i++) {
            generator.nextId();
        }
        assertThat(generator.prefetchedSegments()).isZero();
        assertThat(generator.sourceCalls()).isEqualTo(10);
    }

    @Test
    void concurrentIdsMustNeverRepeat() throws Exception {
        int threads = 8;
        int perThread = 5_000;
        int total = threads * perThread;

        SegmentIdGenerator generator = new SegmentIdGenerator(new InMemorySegmentSource(), 100, 0.2, Runnable::run);
        Set<Long> ids = ConcurrentHashMap.newKeySet(total);
        AtomicLong duplicates = new AtomicLong();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int t = 0; t < threads; t++) {
                pool.execute(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < perThread; i++) {
                            if (!ids.add(generator.nextId())) {
                                duplicates.incrementAndGet();
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(duplicates.get()).isZero();
        assertThat(ids).hasSize(total);
        // 允许因为丢弃预取号段产生的空洞，但每个号段最多浪费 step 个
        List<Long> sorted = new ArrayList<>(ids);
        sorted.sort(Long::compareTo);
        assertThat(sorted.get(0)).isEqualTo(1L);
        assertThat(sorted.get(sorted.size() - 1)).isLessThanOrEqualTo((long) total + 100L * generator.sourceCalls());
    }

    @Test
    void invalidArgumentsShouldFailFast() {
        InMemorySegmentSource source = new InMemorySegmentSource();
        assertThatThrownBy(() -> new SegmentIdGenerator(null, 10, 0.2, Runnable::run))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SegmentIdGenerator(source, 0, 0.2, Runnable::run))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SegmentIdGenerator(source, 10, 1.5, Runnable::run))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
