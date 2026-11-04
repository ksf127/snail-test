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
}
