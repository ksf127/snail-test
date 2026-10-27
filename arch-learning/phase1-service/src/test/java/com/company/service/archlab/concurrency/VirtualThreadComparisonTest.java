package com.company.service.archlab.concurrency;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VirtualThreadComparisonTest {

    private final VirtualThreadComparison comparison = new VirtualThreadComparison();

    @Test
    void virtualThreadsShouldBeatBoundedPlatformPoolOnBlockingWork() {
        // 100 个任务、每任务阻塞 20ms：平台池 10 个线程至少 10 轮 ≈ 200ms，
        // 虚拟线程理论上只需 1 轮 ≈ 20ms，留足余量只断言"更快"。
        VirtualThreadComparison.Comparison result = comparison.compare(100, 20L, 10);

        assertThat(result.platform().threads()).isEqualTo(10);
        assertThat(result.virtualThreads().threads()).isEqualTo(100);
        assertThat(result.platform().elapsedMillis()).isGreaterThanOrEqualTo(180L);
        assertThat(result.speedup()).isGreaterThan(1.0);
    }

    @Test
    void shouldRejectOutOfRangeParameters() {
        assertThatThrownBy(() -> comparison.compare(0, 10L, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
