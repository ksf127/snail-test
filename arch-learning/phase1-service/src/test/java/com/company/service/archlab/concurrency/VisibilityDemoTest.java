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
}
