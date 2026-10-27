package com.company.service.archlab.scenario;

import com.company.service.archlab.scenario.consistency.ConsistencyLab;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConsistencyLabTest {

    private final ConsistencyLab lab = new ConsistencyLab();

    @Test
    void updateDbThenDeleteCacheShouldLeaveStaleCacheEveryRound() {
        ConsistencyLab.Result result = lab.run(ConsistencyLab.Strategy.UPDATE_DB_THEN_DELETE_CACHE, 100);

        assertThat(result.rounds()).isEqualTo(100);
        // 回填发生在"更库 + 删缓存"之后：回填那一刻数据库已经是新值
        assertThat(result.staleFills()).isEqualTo(100);
        assertThat(result.dirtyRounds()).isEqualTo(100);
        assertThat(result.dirty()).isTrue();
        assertThat(result.finalValue()).isEqualTo("v1");
        assertThat(result.dbValue()).isEqualTo("v2");
    }

    @Test
    void deleteCacheThenUpdateDbShouldAlsoEndDirtyButWithoutStaleFill() {
        ConsistencyLab.Result result = lab.run(ConsistencyLab.Strategy.DELETE_CACHE_THEN_UPDATE_DB, 100);

        // 回填时数据库还是旧值，所以不算"回填即过期"
        assertThat(result.staleFills()).isZero();
        // 但删除过早、回填把旧值放回且无人再删，最终仍然是脏数据
        assertThat(result.dirtyRounds()).isEqualTo(100);
        assertThat(result.finalValue()).isEqualTo("v1");
        // 旧值确实在"更库之后、第二次删除之前"短暂存在过
        assertThat(result.timeline())
                .anyMatch(step -> "v2".equals(step.db()) && "v1".equals(step.cache()));
    }

    @Test
    void delayedDoubleDeleteShouldConvergeAfterTransientStaleWindow() {
        ConsistencyLab.Result result = lab.run(ConsistencyLab.Strategy.DELAYED_DOUBLE_DELETE, 100);

        assertThat(result.staleFills()).isZero();
        assertThat(result.dirtyRounds()).isZero();
        assertThat(result.finalValue()).isEqualTo("v2");
        // 脏值确实出现过：更库之后、第二次删除之前的那个窗口
        assertThat(result.timeline())
                .anyMatch(step -> "v2".equals(step.db()) && "v1".equals(step.cache()));
    }

    @Test
    void lockProtectedShouldHaveNeitherStaleFillNorDirtyRead() {
        ConsistencyLab.Result result = lab.run(ConsistencyLab.Strategy.LOCK_PROTECTED, 100);

        assertThat(result.staleFills()).isZero();
        assertThat(result.dirtyRounds()).isZero();
        assertThat(result.finalValue()).isEqualTo("v2");
        // 回填发生在 writer 提交之前（那时数据库还是旧值），
        // 之后的"更库 + 删缓存"在锁内完成，不会再有人把旧值塞回来
        assertThat(result.timeline())
                .filteredOn(step -> step.action().startsWith("reader 回填"))
                .allSatisfy(step -> assertThat(step.db()).isEqualTo("v1"));
    }

    @Test
    void shouldRejectOutOfRangeArguments() {
        assertThatThrownBy(() -> lab.run(null, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("strategy");
        assertThatThrownBy(() -> lab.run(ConsistencyLab.Strategy.LOCK_PROTECTED, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rounds");
        assertThatThrownBy(() -> lab.run(ConsistencyLab.Strategy.LOCK_PROTECTED, 1001))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rounds");
    }
}
