package com.company.service.archlab.cache;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CacheLabTest {

    private final CacheLab lab = new CacheLab(new InMemoryRemoteCache());

    @Test
    void naivePenetrationShouldHitDatabaseForEveryRequest() {
        CacheLab.PenetrationResult result = lab.penetration(20, CacheLab.PenetrationMode.NAIVE);

        assertThat(result.dbLoads()).isEqualTo(20);
        assertThat(result.bloomRejected()).isZero();
    }

    @Test
    void nullCacheShouldLoadDatabaseOnlyOnce() {
        CacheLab.PenetrationResult result = lab.penetration(100, CacheLab.PenetrationMode.NULL_CACHE);

        assertThat(result.dbLoads()).isEqualTo(1);
        assertThat(result.bloomRejected()).isZero();
    }

    @Test
    void bloomFilterShouldRejectAllMissingRequests() {
        CacheLab.PenetrationResult result = lab.penetration(100, CacheLab.PenetrationMode.BLOOM);

        assertThat(result.dbLoads()).isZero();
        assertThat(result.bloomRejected()).isEqualTo(100);
    }

    @Test
    void naiveBreakdownShouldLoadOncePerThread() {
        CacheLab.BreakdownResult result = lab.breakdown(8, 20, CacheLab.BreakdownMode.NAIVE);

        assertThat(result.dbLoads()).isEqualTo(8);
    }

    @Test
    void singleFlightBreakdownShouldLoadOnlyOnce() {
        CacheLab.BreakdownResult result = lab.breakdown(8, 20, CacheLab.BreakdownMode.SINGLE_FLIGHT);

        assertThat(result.dbLoads()).isEqualTo(1);
    }

    @Test
    void fixedTtlShouldReloadAllKeysTogether() {
        CacheLab.AvalancheResult result = lab.avalanche(20, 150, false);

        assertThat(result.keys()).isEqualTo(20);
        assertThat(result.reloads()).isEqualTo(20);
    }

    @Test
    void jitteredTtlShouldSpreadReloads() {
        CacheLab.AvalancheResult result = lab.avalanche(50, 300, true);

        assertThat(result.reloads()).isLessThan(50);
    }

    @Test
    void l1ShouldAbsorbMostHotKeyTraffic() {
        CacheLab.HotKeyResult result = lab.hotKey(1000, true);

        assertThat(result.dbLoads()).isEqualTo(50);
        assertThat(result.l1Hits()).isGreaterThanOrEqualTo(900);
        assertThat(result.l1HitRate()).isGreaterThan(0.8);
    }

    @Test
    void withoutL1HotKeyTrafficShouldFallToL2() {
        CacheLab.HotKeyResult result = lab.hotKey(1000, false);

        assertThat(result.l1Hits()).isZero();
        assertThat(result.l2Hits()).isPositive();
        assertThat(result.dbLoads()).isEqualTo(50);
    }

    @Test
    void shouldRejectOutOfRangeArguments() {
        assertThatThrownBy(() -> lab.penetration(0, CacheLab.PenetrationMode.NAIVE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requests");
        assertThatThrownBy(() -> lab.breakdown(65, 0, CacheLab.BreakdownMode.NAIVE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("threads");
        assertThatThrownBy(() -> lab.avalanche(51, 100, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("keys");
    }
}
