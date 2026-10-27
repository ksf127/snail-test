package com.company.service.archlab.concurrency;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BoundedBufferTest {

    /** 毒丸：生产者全部结束后由测试线程投放，消费者收到即退出，避免"消费者误判已完成"的竞态。 */
    private static final int POISON = -1;

    @Test
    void shouldNotLoseOrDuplicateItemsUnderContention() throws Exception {
        BoundedBuffer<Integer> buffer = new BoundedBuffer<>(16);
        int producers = 2;
        int consumers = 2;
        int itemsPerProducer = 2_000;
        int total = producers * itemsPerProducer;
        Set<Integer> consumed = ConcurrentHashMap.newKeySet();
        CountDownLatch producersDone = new CountDownLatch(producers);
        CountDownLatch consumersDone = new CountDownLatch(consumers);
        ExecutorService pool = Executors.newFixedThreadPool(producers + consumers);
        try {
            for (int p = 0; p < producers; p++) {
                int base = p * itemsPerProducer;
                pool.execute(() -> {
                    try {
                        for (int i = 0; i < itemsPerProducer; i++) {
                            buffer.put(base + i);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        producersDone.countDown();
                    }
                });
            }
            for (int c = 0; c < consumers; c++) {
                pool.execute(() -> {
                    try {
                        while (true) {
                            Integer value = buffer.take();
                            if (value == POISON) {
                                return;
                            }
                            consumed.add(value);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        consumersDone.countDown();
                    }
                });
            }
            assertThat(producersDone.await(15, TimeUnit.SECONDS)).as("生产者应在超时前结束").isTrue();
            buffer.put(POISON);
            buffer.put(POISON);
            assertThat(consumersDone.await(15, TimeUnit.SECONDS)).as("消费者应在收到毒丸后结束").isTrue();
        } finally {
            pool.shutdownNow();
        }
        assertThat(consumed).hasSize(total);
        assertThat(buffer.size()).isZero();
    }
}
