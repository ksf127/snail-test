package com.company.starter.lock;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReentrantTokenRegistryTest {

    private final ReentrantTokenRegistry registry = new ReentrantTokenRegistry();

    @Test
    void shouldTrackReentrantCountPerThread() {
        assertThat(registry.peek("k")).isNull();

        registry.register("k", "t1");
        registry.increment("k");

        assertThat(registry.peek("k").token()).isEqualTo("t1");
        assertThat(registry.peek("k").count()).isEqualTo(2);

        assertThat(registry.release("k")).isFalse();
        assertThat(registry.release("k")).isTrue();
        assertThat(registry.peek("k")).isNull();
    }

    @Test
    void shouldReturnFalseWhenReleasingUnheldKey() {
        assertThat(registry.release("missing")).isFalse();
    }

    @Test
    void shouldNotShareStateAcrossThreads() throws Exception {
        registry.register("k", "main");

        Thread other = new Thread(() -> assertThat(registry.peek("k")).isNull());
        other.start();
        other.join();

        assertThat(registry.peek("k").token()).isEqualTo("main");
    }
}
