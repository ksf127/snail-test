package com.company.starter.lock;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class CompanyLockAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CompanyLockAutoConfiguration.class));

    @Test
    void shouldDefaultToMemoryProvider() {
        runner.run(context -> {
            DistributedLock lock = context.getBean(DistributedLock.class);

            assertThat(lock).isInstanceOf(InMemoryDistributedLock.class);
            assertThat(lock.provider()).isEqualTo("memory");
        });
    }

    @Test
    void shouldBindKeyPrefix() {
        runner.withPropertyValues("company.lock.key-prefix=lab:lock:").run(context -> {
            DistributedLock lock = context.getBean(DistributedLock.class);

            try (LockHandle handle = lock.tryLock("order:1", Duration.ofSeconds(1))) {
                assertThat(handle.key()).isEqualTo("lab:lock:order:1");
            }
        });
    }

    @Test
    void shouldNotCreateRedisLockWithoutRedisTemplate() {
        runner.withPropertyValues("company.lock.provider=redis")
                .run(context -> assertThat(context).doesNotHaveBean(DistributedLock.class));
    }

    @Test
    void shouldBackOffWhenBusinessProvidesItsOwnLock() {
        DistributedLock custom = new InMemoryDistributedLock("custom:");

        runner.withBean(DistributedLock.class, () -> custom)
                .run(context -> assertThat(context.getBean(DistributedLock.class)).isSameAs(custom));
    }

    @Test
    void shouldFailFastOnUnknownProvider() {
        // provider 拼错时必须启动失败，不能静默降级成内存锁——那会在多实例部署时才暴露
        runner.withPropertyValues("company.lock.provider=zookeeper")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalArgumentException.class);
                });
    }
}
