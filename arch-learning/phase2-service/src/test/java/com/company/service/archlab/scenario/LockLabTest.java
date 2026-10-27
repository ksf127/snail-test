package com.company.service.archlab.scenario;

import com.company.service.archlab.scenario.lock.LockLab;
import com.company.starter.lock.InMemoryDistributedLock;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LockLabTest {

    private final LockLab lab = new LockLab(new InMemoryDistributedLock("test:"));

    @Test
    void unlockedCounterShouldLoseUpdates() {
        LockLab.Result result = lab.counter(false, 8, 200);

        assertThat(result.expected()).isEqualTo(1600);
        assertThat(result.actual()).isLessThan(result.expected());
    }

    @Test
    void lockedCounterShouldMatchExpected() {
        LockLab.Result result = lab.counter(true, 8, 100);

        assertThat(result.provider()).isEqualTo("memory");
        assertThat(result.expected()).isEqualTo(800);
        assertThat(result.actual()).isEqualTo(800);
    }

    @Test
    void shouldRejectOutOfRangeArguments() {
        assertThatThrownBy(() -> lab.counter(false, 0, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("threads");
        assertThatThrownBy(() -> lab.counter(false, 8, 1001))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("iterations");
    }
}
