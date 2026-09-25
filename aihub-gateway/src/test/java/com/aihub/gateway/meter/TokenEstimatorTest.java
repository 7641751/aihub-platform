package com.aihub.gateway.meter;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 估算口径只服务一个场景：上游没给 usage（客户端断连 / 上游不支持 include_usage）时的兜底。
 * 口径写死在代码与 README 里，避免「同一份数据每次估算结果都不一样」。
 */
class TokenEstimatorTest {

    @Test
    void countsChineseCharactersAsPointSixTokens() {
        // 10 个汉字 → ceil(10 * 0.6) = 6
        assertThat(TokenEstimator.estimate("你好你好你好你好你好")).isEqualTo(6);
    }

    @Test
    void countsAsciiAsPointThreeTokens() {
        // 10 个 ASCII → ceil(10 * 0.3) = 3
        assertThat(TokenEstimator.estimate("abcdefghij")).isEqualTo(3);
    }

    @Test
    void neverReturnsZeroForNonEmptyText() {
        assertThat(TokenEstimator.estimate("a")).isEqualTo(1);
        assertThat(TokenEstimator.estimate("你")).isEqualTo(1);
    }

    @Test
    void returnsZeroForEmptyOrNull() {
        assertThat(TokenEstimator.estimate(null)).isZero();
        assertThat(TokenEstimator.estimate("")).isZero();
    }
}
