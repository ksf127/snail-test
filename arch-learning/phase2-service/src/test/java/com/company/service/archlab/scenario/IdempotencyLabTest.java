package com.company.service.archlab.scenario;

import com.company.service.archlab.scenario.idempotency.IdempotencyLab;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyLabTest {

    private final IdempotencyLab lab = new IdempotencyLab();

    @Test
    void noneModeShouldExecuteEveryRequest() {
        IdempotencyLab.Result result = lab.submit(IdempotencyLab.Mode.NONE, 50);

        assertThat(result.requests()).isEqualTo(50);
        assertThat(result.businessExecutions()).isEqualTo(50);
        assertThat(result.successResponses()).isEqualTo(50);
        assertThat(result.rejected()).isZero();
    }

    @Test
    void tokenModeShouldExecuteOnlyOnce() {
        IdempotencyLab.Result result = lab.submit(IdempotencyLab.Mode.TOKEN, 50);

        assertThat(result.businessExecutions()).isEqualTo(1);
        assertThat(result.successResponses()).isEqualTo(1);
        assertThat(result.rejected()).isEqualTo(49);
    }

    @Test
    void uniqueIndexModeShouldExecuteOnlyOnce() {
        IdempotencyLab.Result result = lab.submit(IdempotencyLab.Mode.UNIQUE_INDEX, 50);

        assertThat(result.businessExecutions()).isEqualTo(1);
        assertThat(result.successResponses()).isEqualTo(1);
        assertThat(result.rejected()).isEqualTo(49);
    }

    @Test
    void stateMachineModeShouldExecuteOnceButAckEveryCallback() {
        IdempotencyLab.Result result = lab.submit(IdempotencyLab.Mode.STATE_MACHINE, 50);

        assertThat(result.businessExecutions()).isEqualTo(1);
        // 重复回调幂等应答：对外都算成功，但不重复执行
        assertThat(result.successResponses()).isEqualTo(50);
        assertThat(result.rejected()).isZero();
    }

    @Test
    void shouldRejectOutOfRangeArguments() {
        assertThatThrownBy(() -> lab.submit(null, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mode");
        assertThatThrownBy(() -> lab.submit(IdempotencyLab.Mode.TOKEN, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requests");
        assertThatThrownBy(() -> lab.submit(IdempotencyLab.Mode.TOKEN, 201))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requests");
    }
}
