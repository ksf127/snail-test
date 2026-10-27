package com.company.service.archlab.concurrency;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CounterComparisonTest {

    private final CounterComparison comparison = new CounterComparison();

    @Test
    void shouldKeepAllIncrementsForThreadSafeStrategies() {
        long expected = 4L * 20_000L;
        for (CounterComparison.Strategy strategy : new CounterComparison.Strategy[]{
                CounterComparison.Strategy.SYNCHRONIZED,
                CounterComparison.Strategy.ATOMIC_LONG,
                CounterComparison.Strategy.LONG_ADDER,
                CounterComparison.Strategy.SPIN_LOCK}) {
            CounterComparison.Result result = comparison.run(strategy, 4, 20_000L);
            assertThat(result.actual()).as(strategy.name()).isEqualTo(expected);
            assertThat(result.correct()).as(strategy.name()).isTrue();
        }
    }

    @Test
    void shouldRejectOutOfRangeParameters() {
        assertThatThrownBy(() -> comparison.run(CounterComparison.Strategy.ATOMIC_LONG, 0, 100L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> comparison.run(CounterComparison.Strategy.ATOMIC_LONG, 4, 0L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
