package com.company.starter.lock;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 只验证锁语义，不验证 Redis：内存实现是其余实现的行为基线。
 */
class InMemoryDistributedLockTest {

    private final InMemoryDistributedLock lock = new InMemoryDistributedLock("test:lock:");

    @Test
    void shouldExcludeOtherThreads() throws Exception {
        try (LockHandle ignored = lock.tryLock("order:1", Duration.ofSeconds(5))) {
            assertThat(ignored).isNotNull();

            LockHandle other = onOtherThread(() -> lock.tryLock("order:1", Duration.ofSeconds(5)));

            assertThat(other).isNull();
        }
    }

    @Test
    void shouldBeReentrantForSameThread() throws Exception {
        LockHandle outer = lock.tryLock("order:2", Duration.ofSeconds(5));
        LockHandle inner = lock.tryLock("order:2", Duration.ofSeconds(5));

        assertThat(inner).isNotNull();
        assertThat(onOtherThread(() -> lock.tryLock("order:2", Duration.ofSeconds(5)))).isNull();

        // 内层释放不应把锁交出去
        inner.unlock();
        assertThat(onOtherThread(() -> lock.tryLock("order:2", Duration.ofSeconds(5)))).isNull();

        outer.unlock();
        LockHandle afterRelease = onOtherThread(() -> lock.tryLock("order:2", Duration.ofSeconds(5)));
        assertThat(afterRelease).isNotNull();
        afterRelease.unlock();
    }

    @Test
    void shouldAllowStealAfterLeaseExpiry() throws Exception {
        LockHandle expired = lock.tryLock("order:3", Duration.ofMillis(50));
        assertThat(expired).isNotNull();

        Thread.sleep(120);

        LockHandle stolen = onOtherThread(() -> lock.tryLock("order:3", Duration.ofSeconds(5)));
        assertThat(stolen).isNotNull();
        stolen.unlock();
    }

    @Test
    void shouldNotReleaseLockStolenByAnotherThread() throws Exception {
        LockHandle expired = lock.tryLock("order:4", Duration.ofMillis(50));
        Thread.sleep(120);

        LockHandle stolen = onOtherThread(() -> lock.tryLock("order:4", Duration.ofSeconds(5)));
        assertThat(stolen).isNotNull();

        // 旧持有者释放：不能删掉新持有者的锁
        expired.unlock();

        assertThat(onOtherThread(() -> lock.tryLock("order:4", Duration.ofSeconds(5)))).isNull();
    }

    @Test
    void shouldPrefixKeys() {
        try (LockHandle handle = lock.tryLock("order:5", Duration.ofSeconds(5))) {
            assertThat(handle.key()).isEqualTo("test:lock:order:5");
        }
    }

    @Test
    void shouldSerializeIncrementUnderContention() throws Exception {
        int threads = 8;
        int perThread = 200;
        AtomicInteger counter = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    for (int j = 0; j < perThread; j++) {
                        LockHandle handle = lock.lock("counter", Duration.ofSeconds(5), Duration.ofSeconds(5));
                        assertThat(handle).isNotNull();
                        try {
                            int current = counter.get();
                            counter.set(current + 1);
                        } finally {
                            handle.unlock();
                        }
                    }
                });
            }
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(counter.get()).isEqualTo(threads * perThread);
    }

    private static <T> T onOtherThread(java.util.concurrent.Callable<T> callable) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<T> future = executor.submit(callable);
            return future.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }
}
