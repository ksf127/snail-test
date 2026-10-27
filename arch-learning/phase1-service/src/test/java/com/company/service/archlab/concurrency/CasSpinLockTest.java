package com.company.service.archlab.concurrency;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CasSpinLockTest {

    @Test
    void shouldGuaranteeMutualExclusion() throws Exception {
        CasSpinLock lock = new CasSpinLock();
        long[] value = {0L};
        int threads = 8;
        int incrementsPerThread = 20_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch done = new CountDownLatch(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.execute(() -> {
                    try {
                        for (int j = 0; j < incrementsPerThread; j++) {
                            lock.lock();
                            try {
                                value[0]++;
                            } finally {
                                lock.unlock();
                            }
                        }
                    } finally {
                        done.countDown();
                    }
                });
            }
            done.await();
        } finally {
            pool.shutdownNow();
        }
        assertThat(value[0]).isEqualTo((long) threads * incrementsPerThread);
        assertThat(lock.isLocked()).isFalse();
    }

    @Test
    void shouldRejectReentrantLock() {
        CasSpinLock lock = new CasSpinLock();
        lock.lock();
        try {
            assertThatThrownBy(lock::lock).isInstanceOf(IllegalStateException.class);
        } finally {
            lock.unlock();
        }
    }

    @Test
    void shouldRejectUnlockByNonOwner() {
        CasSpinLock lock = new CasSpinLock();
        assertThatThrownBy(lock::unlock).isInstanceOf(IllegalStateException.class);
    }
}
