package com.company.starter.threadpool;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 与 company-mq-starter 的测试风格一致：只验证装配结果，不起网络、不起线程风暴。
 */
class CompanyThreadPoolAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CompanyThreadPoolAutoConfiguration.class))
            .withPropertyValues(
                    "company.thread-pool.executors.order-async.core-pool-size=1",
                    "company.thread-pool.executors.order-async.max-pool-size=2",
                    "company.thread-pool.executors.order-async.queue-capacity=2");

    @Test
    void shouldCreateConfiguredPools() {
        runner.run(context -> {
            ThreadPoolManager manager = context.getBean(ThreadPoolManager.class);

            assertThat(manager.contains("order-async")).isTrue();
            ResizableThreadPoolExecutor executor = manager.get("order-async");
            assertThat(executor.getCorePoolSize()).isEqualTo(1);
            assertThat(executor.getMaximumPoolSize()).isEqualTo(2);
            assertThat(executor.getQueueCapacity()).isEqualTo(2);
            assertThat(manager.snapshot()).hasSize(1);
        });
    }

    @Test
    void shouldResizeAtRuntime() {
        runner.run(context -> {
            ThreadPoolManager manager = context.getBean(ThreadPoolManager.class);

            manager.resize("order-async", 2, 4, 16);

            ResizableThreadPoolExecutor executor = manager.get("order-async");
            assertThat(executor.getCorePoolSize()).isEqualTo(2);
            assertThat(executor.getMaximumPoolSize()).isEqualTo(4);
            assertThat(executor.getQueueCapacity()).isEqualTo(16);
            assertThat(manager.snapshot().get(0).queueCapacity()).isEqualTo(16);
        });
    }

    @Test
    void shouldCountRejectedTasks() {
        runner.withPropertyValues(
                        "company.thread-pool.executors.order-async.max-pool-size=1",
                        "company.thread-pool.executors.order-async.queue-capacity=1")
                .run(context -> {
                    ResizableThreadPoolExecutor executor =
                            context.getBean(ThreadPoolManager.class).get("order-async");
                    CountDownLatch release = new CountDownLatch(1);
                    try {
                        executor.execute(() -> await(release));
                        executor.execute(() -> await(release));

                        assertThatThrownBy(() -> executor.execute(() -> { }))
                                .isInstanceOf(RejectedExecutionException.class);
                        assertThat(executor.getRejectedCount()).isEqualTo(1);
                    } finally {
                        release.countDown();
                    }
                });
    }

    @Test
    void shouldBackOffWhenDisabled() {
        runner.withPropertyValues("company.thread-pool.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(ThreadPoolManager.class));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
