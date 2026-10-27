package com.company.service.archlab.pattern.statemachine;

import com.company.common.core.BizException;
import com.company.common.core.ErrorCode;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderStateMachineTest {

    private final OrderStateMachine stateMachine = new OrderStateMachine();

    @Test
    void shouldWalkHappyPathFromCreatedToCompleted() {
        OrderStateMachine.Transition paid = stateMachine.transit(OrderState.CREATED, OrderEvent.PAY);
        assertThat(paid.to()).isEqualTo(OrderState.PAID);

        OrderStateMachine.Transition shipped = stateMachine.transit(paid.to(), OrderEvent.SHIP);
        assertThat(shipped.to()).isEqualTo(OrderState.SHIPPED);

        OrderStateMachine.Transition completed = stateMachine.transit(shipped.to(), OrderEvent.COMPLETE);
        assertThat(completed.to()).isEqualTo(OrderState.COMPLETED);
    }

    @Test
    void shouldAllowCancelAfterPaid() {
        assertThat(stateMachine.transit(OrderState.PAID, OrderEvent.CANCEL).to())
                .isEqualTo(OrderState.CANCELLED);
    }

    @Test
    void shouldRejectIllegalTransitionWithConflict() {
        assertThatThrownBy(() -> stateMachine.transit(OrderState.CREATED, OrderEvent.SHIP))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("PAY")
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    void shouldExposeAllowedEventsPerState() {
        assertThat(stateMachine.allowedEvents(OrderState.CREATED))
                .containsExactlyInAnyOrder(OrderEvent.PAY, OrderEvent.CANCEL);
        assertThat(stateMachine.allowedEvents(OrderState.SHIPPED))
                .containsExactly(OrderEvent.COMPLETE);
        assertThat(stateMachine.allowedEvents(OrderState.COMPLETED)).isEmpty();
    }

    @Test
    void shouldRejectNullArguments() {
        assertThatThrownBy(() -> stateMachine.transit(null, OrderEvent.PAY))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.PARAM_INVALID);
        assertThatThrownBy(() -> stateMachine.transit(OrderState.CREATED, null))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.PARAM_INVALID);
    }
}
