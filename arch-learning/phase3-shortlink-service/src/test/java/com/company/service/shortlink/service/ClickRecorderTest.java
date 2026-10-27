package com.company.service.shortlink.service;

import com.company.service.shortlink.infra.MemoryClickStore;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ClickRecorderTest {

    @Test
    void flushShouldMovePendingIncrementsIntoStore() {
        MemoryClickStore store = new MemoryClickStore();
        ClickRecorder recorder = new ClickRecorder(store);

        for (int i = 0; i < 5; i++) {
            recorder.record("0000001");
        }
        recorder.record("0000002");

        assertThat(recorder.pending("0000001")).isEqualTo(5);
        assertThat(recorder.clicks("0000001")).isEqualTo(5);
        assertThat(store.clicks("0000001")).isZero();

        recorder.flush();

        assertThat(store.clicks("0000001")).isEqualTo(5);
        assertThat(store.clicks("0000002")).isEqualTo(1);
        assertThat(recorder.pendingTotal()).isZero();
        assertThat(recorder.recordedTotal()).isEqualTo(6);
        assertThat(recorder.flushedTotal()).isEqualTo(6);
        assertThat(recorder.clicks("0000001")).isEqualTo(5);
    }

    @Test
    void flushShouldNotLoseConcurrentIncrements() throws Exception {
        MemoryClickStore store = new MemoryClickStore();
        ClickRecorder recorder = new ClickRecorder(store);
        int threads = 8;
        int perThread = 10_000;

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        ExecutorService flusher = Executors.newSingleThreadExecutor();
        try {
            for (int t = 0; t < threads; t++) {
                pool.execute(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < perThread; i++) {
                            recorder.record("hot");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            flusher.execute(() -> {
                while (done.getCount() > 0) {
                    recorder.flush();
                    java.util.concurrent.locks.LockSupport.parkNanos(200_000L);
                }
            });
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
            flusher.shutdownNow();
        }

        recorder.flush();
        assertThat(recorder.recordedTotal()).isEqualTo((long) threads * perThread);
        assertThat(store.clicks("hot")).isEqualTo((long) threads * perThread);
        assertThat(recorder.pendingTotal()).isZero();
    }
}
