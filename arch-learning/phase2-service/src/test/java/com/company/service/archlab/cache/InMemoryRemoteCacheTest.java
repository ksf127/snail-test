package com.company.service.archlab.cache;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryRemoteCacheTest {

    private final InMemoryRemoteCache cache = new InMemoryRemoteCache();

    @Test
    void shouldStoreAndReadValue() {
        cache.put("k", "v", Duration.ofMinutes(1));

        assertThat(cache.get("k")).isEqualTo("v");
        assertThat(cache.size()).isEqualTo(1);
    }

    @Test
    void shouldReturnNullAfterTtlExpired() throws InterruptedException {
        cache.put("k", "v", Duration.ofMillis(1));

        Thread.sleep(50);

        assertThat(cache.get("k")).isNull();
        assertThat(cache.size()).isZero();
    }

    @Test
    void shouldRemoveOnEvict() {
        cache.put("k", "v", Duration.ofMinutes(1));

        cache.evict("k");

        assertThat(cache.get("k")).isNull();
    }

    @Test
    void shouldTreatNonPositiveTtlAsNeverExpire() throws InterruptedException {
        cache.put("k", "v", Duration.ZERO);

        Thread.sleep(20);

        assertThat(cache.get("k")).isEqualTo("v");
    }
}
