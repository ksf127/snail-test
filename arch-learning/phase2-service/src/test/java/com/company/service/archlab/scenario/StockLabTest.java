package com.company.service.archlab.scenario;

import com.company.service.archlab.scenario.stock.StockLab;
import com.company.starter.lock.InMemoryDistributedLock;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StockLabTest {

    private final StockLab lab = new StockLab(new InMemoryDistributedLock("test:"));

    @Test
    void noLockShouldOversell() {
        StockLab.Result result = lab.run(StockLab.Mode.NONE, 10, 20, 1);

        assertThat(result.sold()).isGreaterThan(result.initialStock());
        assertThat(result.oversold()).isPositive();
        assertThat(result.consistent()).isFalse();
    }

    @Test
    void localLockShouldFailAcrossInstances() {
        // 2 个实例各有一把本地锁，1 件库存被卖成 2 件
        StockLab.Result result = lab.run(StockLab.Mode.LOCAL_LOCK, 1, 2, 2);

        assertThat(result.sold()).isEqualTo(2);
        assertThat(result.remaining()).isZero();
        assertThat(result.oversold()).isEqualTo(1);
        assertThat(result.consistent()).isFalse();
    }

    @Test
    void optimisticLockShouldKeepInvariant() {
        StockLab.Result result = lab.run(StockLab.Mode.OPTIMISTIC, 10, 20, 1);

        assertThat(result.sold()).isEqualTo(10);
        assertThat(result.remaining()).isZero();
        assertThat(result.oversold()).isZero();
        assertThat(result.consistent()).isTrue();
    }

    @Test
    void rowLockShouldKeepInvariant() {
        StockLab.Result result = lab.run(StockLab.Mode.ROW_LOCK, 10, 20, 1);

        assertThat(result.sold()).isEqualTo(10);
        assertThat(result.remaining()).isZero();
        assertThat(result.consistent()).isTrue();
    }

    @Test
    void redisLockShouldKeepInvariant() {
        StockLab.Result result = lab.run(StockLab.Mode.REDIS_LOCK, 10, 20, 3);

        assertThat(result.sold()).isEqualTo(10);
        assertThat(result.remaining()).isZero();
        assertThat(result.oversold()).isZero();
        assertThat(result.consistent()).isTrue();
    }

    @Test
    void shouldRejectOutOfRangeArguments() {
        assertThatThrownBy(() -> lab.run(null, 10, 20, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mode");
        assertThatThrownBy(() -> lab.run(StockLab.Mode.NONE, 0, 20, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stock");
        assertThatThrownBy(() -> lab.run(StockLab.Mode.NONE, 10, 201, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("buyers");
        assertThatThrownBy(() -> lab.run(StockLab.Mode.NONE, 10, 20, 9))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("instances");
    }
}
