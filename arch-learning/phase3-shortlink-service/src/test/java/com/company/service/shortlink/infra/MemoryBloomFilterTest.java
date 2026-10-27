package com.company.service.shortlink.infra;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemoryBloomFilterTest {

    @Test
    void addedKeysMustNeverBeReportedAsAbsent() {
        MemoryBloomFilter filter = new MemoryBloomFilter(10_000, 0.0001);
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            String key = "key-" + i;
            keys.add(key);
            filter.add(key);
        }
        for (String key : keys) {
            assertThat(filter.mightContain(key)).as("假阴性为零: %s", key).isTrue();
        }
        assertThat(filter.addedCount()).isEqualTo(10_000);
        assertThat(filter.memoryBytes()).isGreaterThan(0);
        assertThat(filter.hashCount()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void falsePositiveRateShouldStayWithinTheoreticalBound() {
        long expectedInsertions = 100_000;
        double configuredRate = 0.001;
        MemoryBloomFilter filter = new MemoryBloomFilter(expectedInsertions, configuredRate);
        for (int i = 0; i < expectedInsertions; i++) {
            filter.add("present-" + i);
        }

        int probes = 100_000;
        int falsePositives = 0;
        for (int i = 0; i < probes; i++) {
            if (filter.mightContain("absent-" + i)) {
                falsePositives++;
            }
        }
        double observed = falsePositives / (double) probes;
        // 理论值是 p，实测有波动；给出 3 倍上界，避免偶发抖动导致构建失败
        assertThat(observed).as("观测误判率 %s（配置 %s）", observed, configuredRate)
                .isLessThan(configuredRate * 3);
    }

    @Test
    void emptyFilterShouldRejectEverything() {
        MemoryBloomFilter filter = new MemoryBloomFilter(1_000, 0.001);
        for (int i = 0; i < 1_000; i++) {
            assertThat(filter.mightContain("nothing-" + i)).isFalse();
        }
    }

    @Test
    void invalidArgumentsShouldFailFast() {
        assertThatThrownBy(() -> new MemoryBloomFilter(0, 0.001))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MemoryBloomFilter(1_000, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MemoryBloomFilter(1_000, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
