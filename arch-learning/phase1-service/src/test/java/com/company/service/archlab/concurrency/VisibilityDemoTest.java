package com.company.service.archlab.concurrency;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VisibilityDemoTest {

    private final VisibilityDemo demo = new VisibilityDemo();

    @Test
    void volatileFlagShouldBeVisible() {
        VisibilityDemo.Result result = demo.run(true, 2_000L);
        assertThat(result.useVolatile()).isTrue();
        assertThat(result.observed()).isTrue();
    }

    @Test
    void shouldReturnAfterTimeoutWithoutHanging() {
        // 写线程 50ms 后才写，读线程 5ms 就超时：必须安全返回，而不是把请求线程挂死
        VisibilityDemo.Result result = demo.run(false, 5L);
        assertThat(result.observed()).isFalse();
        assertThat(result.elapsedMillis()).isEqualTo(5L);
    }

    @Test
    void volatileReadShouldNeverBeHoistedOutOfLoop() {
        // volatile 读不允许被提出循环，所以主线程一置位，读线程就该看到并退出。
        // 这条断言在任何 CPU 架构上都成立，可以放心写死。
        VisibilityDemo.HoistResult result = demo.runHoisting(true, 300L);
        assertThat(result.useVolatile()).isTrue();
        assertThat(result.sawWrite()).isTrue();
        assertThat(result.spins()).isLessThan(result.spinLimit());
    }

    @Test
    void plainReadHoistingMustNotHangTheCaller() {
        // 普通字段是否被提升取决于 JIT 现场决策（解释执行阶段不会提升，
        // 编译后同一个循环就再也看不到写入了），所以这里不能断言 sawWrite 的具体值。
        // 能断言的只有一件事：无论提不提升，方法都必须在有界时间内返回，不能把调用方挂死。
        VisibilityDemo.HoistResult result = demo.runHoisting(false, 300L);
        assertThat(result.useVolatile()).isFalse();
        assertThat(result.spins()).isPositive();
        assertThat(result.spins()).isLessThanOrEqualTo(result.spinLimit());
    }

    @Test
    void hoistingShouldRejectBadSettleMillis() {
        IllegalArgumentException error = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> demo.runHoisting(true, 0L));
        assertThat(error.getMessage()).contains("settleMillis");
    }

    @Test
    void compareShouldReturnBothGroupsAndNeverStuckOnVolatile() {
        // 用最小参数把耗时压到亚秒级：只为验证结构与 volatile 的确定性，
        // 频次规律本身要靠真实参数的接口去观察，不适合写成断言（受 JIT 现场决策影响）
        VisibilityDemo.CompareResult result = demo.compareHoisting(100L, 1);

        assertThat(result.trials()).isEqualTo(1);
        assertThat(result.plainRuns()).hasSize(1);
        assertThat(result.volatileRuns()).hasSize(1);
        // volatile 读禁止被提升，这条断言在任何架构上都成立
        assertThat(result.volatileStuck()).isZero();
        assertThat(result.volatileRuns().get(0).sawWrite()).isTrue();
    }

    @Test
    void compareShouldRejectTrialsOutOfRange() {
        IllegalArgumentException tooFew = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> demo.compareHoisting(100L, 0));
        assertThat(tooFew.getMessage()).contains("trials");

        IllegalArgumentException tooMany = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> demo.compareHoisting(100L, 6));
        assertThat(tooMany.getMessage()).contains("trials");
    }
}
