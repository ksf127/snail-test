package com.company.service.shortlink.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Base62Test {

    @Test
    void encodeShouldAlwaysProduceSevenChars() {
        assertThat(Base62.encodeFixed(0)).isEqualTo("0000000");
        assertThat(Base62.encodeFixed(61)).isEqualTo("000000z");
        assertThat(Base62.encodeFixed(62)).isEqualTo("0000010");
        assertThat(Base62.encodeFixed(Base62.MAX_ID)).isEqualTo("zzzzzzz");
    }

    @Test
    void decodeShouldRoundTripEveryBoundary() {
        long[] values = {0, 1, 9, 10, 35, 36, 61, 62, 3843, 3844, 1_000_000L, Base62.MAX_ID};
        for (long value : values) {
            String code = Base62.encodeFixed(value);
            assertThat(code).hasSize(Base62.CODE_LENGTH);
            assertThat(Base62.decode(code)).isEqualTo(value);
            assertThat(Base62.isValid(code)).isTrue();
        }
    }

    @Test
    void encodeShouldRejectOutOfRange() {
        assertThatThrownBy(() -> Base62.encodeFixed(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Base62.encodeFixed(Base62.MAX_ID + 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidCodesShouldBeDetectedWithoutThrowing() {
        assertThat(Base62.isValid(null)).isFalse();
        assertThat(Base62.isValid("000000")).isFalse();
        assertThat(Base62.isValid("00000000")).isFalse();
        assertThat(Base62.isValid("00000-0")).isFalse();
        assertThat(Base62.isValid("00000中0")).isFalse();
        assertThatThrownBy(() -> Base62.decode("00000-0")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void maxIdShouldMatchCapacityOfSevenBase62Digits() {
        assertThat(Base62.MAX_ID).isEqualTo(62L * 62 * 62 * 62 * 62 * 62 * 62 - 1);
    }
}
