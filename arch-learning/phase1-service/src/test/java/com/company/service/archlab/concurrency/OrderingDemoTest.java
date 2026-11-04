package com.company.service.archlab.concurrency;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrderingDemoTest {

    private final OrderingDemo demo = new OrderingDemo();

    @Test
    void volatileMustNeverBeReordered() {
        // volatile 写之后的 StoreLoad 屏障在任何架构的 JVM 实现上都成立，可以放心断言
        OrderingDemo.OrderingResult result = demo.run(true, 20_000);
        assertThat(result.useVolatile()).isTrue();
        assertThat(result.violations()).isZero();
    }

    @Test
    void plainFieldsShouldReturnAValidResult() {
        // 普通字段是否真的撞上重排窗口取决于 CPU 与调度，不能断言 "> 0"，
        // 只能断言结果结构和取值范围合法（本机实测 20 万轮约 25%~35%，但测试不能依赖概率）
        OrderingDemo.OrderingResult result = demo.run(false, 20_000);
        assertThat(result.useVolatile()).isFalse();
        assertThat(result.rounds()).isEqualTo(20_000);
        assertThat(result.violations()).isBetween(0, 20_000);
        assertThat(result.elapsedMillis()).isPositive();
    }

    @Test
    void compareShouldRunBothGroupsAndKeepVolatileClean() {
        OrderingDemo.OrderingCompareResult result = demo.compare(10_000, 1);
        assertThat(result.trials()).isEqualTo(1);
        assertThat(result.plainRuns()).hasSize(1);
        assertThat(result.volatileRuns()).hasSize(1);
        // volatile 组必须恒为 0
        assertThat(result.volatileViolations()).isZero();
        assertThat(result.volatileRuns().get(0).violations()).isZero();
    }

    @Test
    void shouldRejectRoundsOutOfRange() {
        IllegalArgumentException tooFew = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> demo.run(false, 1));
        assertThat(tooFew.getMessage()).contains("rounds");

        IllegalArgumentException tooMany = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> demo.run(true, 2_000_001));
        assertThat(tooMany.getMessage()).contains("rounds");
    }

    @Test
    void compareShouldRejectBadTrials() {
        IllegalArgumentException error = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> demo.compare(10_000, 0));
        assertThat(error.getMessage()).contains("trials");
    }
}
