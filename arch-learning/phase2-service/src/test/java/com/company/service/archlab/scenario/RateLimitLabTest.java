package com.company.service.archlab.scenario;

import java.util.List;

import com.company.service.archlab.scenario.rateLimit.RateLimitLab;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateLimitLabTest {

    private final RateLimitLab lab = new RateLimitLab();

    @Test
    void fixedWindowShouldLetBothBurstsThrough() {
        RateLimitLab.Result result = lab.simulate(RateLimitLab.Mode.FIXED_WINDOW, 10, 1000, null);

        assertThat(result.requests()).isEqualTo(20);
        assertThat(result.allowed()).isEqualTo(20);
        assertThat(result.rejected()).isZero();
    }

    @Test
    void slidingWindowShouldCapToLimitAcrossBoundary() {
        RateLimitLab.Result result = lab.simulate(RateLimitLab.Mode.SLIDING_WINDOW, 10, 1000, null);

        assertThat(result.allowed()).isEqualTo(10);
        assertThat(result.rejected()).isEqualTo(10);
    }

    @Test
    void tokenBucketShouldAllowSmallRefillBurst() {
        RateLimitLab.Result result = lab.simulate(RateLimitLab.Mode.TOKEN_BUCKET, 10, 1000, null);

        // 900ms 用光 10 个令牌，到 1100ms 补回 2 个
        assertThat(result.allowed()).isEqualTo(12);
        assertThat(result.rejected()).isEqualTo(8);
    }

    @Test
    void shouldAcceptExplicitRequestTimes() {
        List<Long> times = List.of(0L, 10L, 20L, 30L);

        RateLimitLab.Result result = lab.simulate(RateLimitLab.Mode.SLIDING_WINDOW, 3, 1000, times);

        assertThat(result.requests()).isEqualTo(4);
        assertThat(result.allowed()).isEqualTo(3);
        assertThat(result.allowedTimes()).containsExactly(0L, 10L, 20L);
    }

    @Test
    void shouldRejectOutOfRangeArguments() {
        assertThatThrownBy(() -> lab.simulate(null, 10, 1000, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mode");
        assertThatThrownBy(() -> lab.simulate(RateLimitLab.Mode.FIXED_WINDOW, 0, 1000, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit");
        assertThatThrownBy(() -> lab.simulate(RateLimitLab.Mode.FIXED_WINDOW, 10, 60_001, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("windowMillis");
        assertThatThrownBy(() -> lab.simulate(RateLimitLab.Mode.FIXED_WINDOW, 10, 1000, List.of(10L, 5L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("非递减");
    }
}
