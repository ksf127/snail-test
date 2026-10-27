package com.company.starter.threadpool;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ResizableLinkedBlockingQueueTest {

    @Test
    void shouldRejectWhenFullAndAcceptAfterGrow() {
        ResizableLinkedBlockingQueue<String> queue = new ResizableLinkedBlockingQueue<>(2, 8);

        assertThat(queue.offer("a")).isTrue();
        assertThat(queue.offer("b")).isTrue();
        assertThat(queue.offer("c")).isFalse();

        assertThat(queue.setCapacity(4)).isEqualTo(2);
        assertThat(queue.offer("c")).isTrue();
        assertThat(queue).hasSize(3);
    }

    @Test
    void shouldWakeBlockedPutterWhenCapacityGrows() throws Exception {
        ResizableLinkedBlockingQueue<String> queue = new ResizableLinkedBlockingQueue<>(1, 4);
        queue.put("a");

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> putter = pool.submit(() -> {
                queue.put("b");
                return null;
            });

            Thread.sleep(100L);
            assertThat(putter.isDone()).as("队列已满，put 必须阻塞").isFalse();

            queue.setCapacity(2);
            putter.get(2, TimeUnit.SECONDS);
            assertThat(queue).hasSize(2);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void shouldKeepQueuedElementsWhenShrink() {
        ResizableLinkedBlockingQueue<String> queue = new ResizableLinkedBlockingQueue<>(4, 8);
        queue.offer("a");
        queue.offer("b");
        queue.offer("c");

        queue.setCapacity(2);

        // 软缩容：已排队的 3 个任务不丢，但新任务进不来
        assertThat(queue).hasSize(3);
        assertThat(queue.remainingCapacity()).isZero();
        assertThat(queue.offer("d")).isFalse();

        assertThat(queue.poll()).isEqualTo("a");
        // 消费掉 1 个后仍有 2 个（刚好等于新容量），只有再消费 1 个才腾出空位
        assertThat(queue.remainingCapacity()).isZero();
        assertThat(queue.poll()).isEqualTo("b");
        assertThat(queue.remainingCapacity()).isEqualTo(1);
    }
}
