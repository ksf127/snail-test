package com.company.service.shortlink.capacity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapacityModelTest {

    @Test
    void oneMillionQpsWithHealthyHitRatiosShouldBeFeasible() {
        CapacityModel.Plan plan = CapacityModel.plan(new CapacityModel.Workload(
                1_000_000, 0.95, 0.9, 150_000, 500_000, 100_000));

        assertThat(plan.nodes()).isEqualTo(7);
        assertThat(plan.qpsPerNode()).isEqualTo(142_858);
        assertThat(plan.l1Qps()).isEqualTo(950_000);
        assertThat(plan.l2Qps()).isEqualTo(50_000);
        assertThat(plan.dbQps()).isEqualTo(5_000);
        assertThat(plan.redisUtilization()).isEqualTo(0.1);
        assertThat(plan.dbUtilization()).isEqualTo(0.05);
        assertThat(plan.feasible()).isTrue();
        assertThat(plan.warnings()).isEmpty();
    }

    @Test
    void lowerL1HitRatioShouldBlowUpRedis() {
        CapacityModel.Plan plan = CapacityModel.plan(new CapacityModel.Workload(
                1_000_000, 0.5, 0.9, 150_000, 100_000, 100_000));

        assertThat(plan.l2Qps()).isEqualTo(500_000);
        assertThat(plan.dbQps()).isEqualTo(50_000);
        assertThat(plan.redisUtilization()).isEqualTo(5.0);
        assertThat(plan.feasible()).isFalse();
        assertThat(plan.warnings()).anySatisfy(w -> assertThat(w).contains("Redis").contains("超过容量"));
    }

    @Test
    void storageShouldWarnWhenAboveSafetyHeadroomButBelowCapacity() {
        // L2 未命中率 50%，100 万 QPS、L1 90% → 5 万 QPS 打到存储
        CapacityModel.Plan plan = CapacityModel.plan(new CapacityModel.Workload(
                1_000_000, 0.9, 0.5, 200_000, 1_000_000, 60_000));

        assertThat(plan.dbQps()).isEqualTo(50_000);
        assertThat(plan.dbUtilization()).isEqualTo(0.8333);
        assertThat(plan.feasible()).isFalse();
        assertThat(plan.warnings()).anySatisfy(w -> assertThat(w).contains("存储").contains("70%"));
    }

    @Test
    void nodeCountShouldRoundUp() {
        CapacityModel.Plan plan = CapacityModel.plan(new CapacityModel.Workload(
                1_000_001, 0.99, 0.99, 150_000, 1_000_000, 1_000_000));

        assertThat(plan.nodes()).isEqualTo(7);
        assertThat(plan.qpsPerNode()).isEqualTo(142_858);
        assertThat(plan.l2Qps()).isEqualTo(10_000);
        assertThat(plan.dbQps()).isEqualTo(100);
    }

    @Test
    void zeroQpsShouldBeRejected() {
        assertThatThrownBy(() -> CapacityModel.plan(new CapacityModel.Workload(
                0, 0.9, 0.9, 150_000, 500_000, 100_000)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ratiosOutsideZeroToOneShouldBeRejected() {
        assertThatThrownBy(() -> CapacityModel.plan(new CapacityModel.Workload(
                1_000, 1.5, 0.9, 150_000, 500_000, 100_000)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CapacityModel.plan(new CapacityModel.Workload(
                1_000, 0.9, -0.1, 150_000, 500_000, 100_000)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
