package com.aihub.gateway.quota;

import com.aihub.common.quota.QuotaKeys;
import com.aihub.common.quota.QuotaScript;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisQuotaAdjustmentTest {

    private static final long TENANT = 7L;
    private static final String PERIOD = "202611";
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
    private final RedisQuotaLimiter limiter = new RedisQuotaLimiter(redis);

    @Test
    void passesSignedDeltaAndBothFieldNamesToTheScript() {
        scriptReturns(1L);

        limiter.adjust(TENANT, PERIOD, 100L, 3L);
        limiter.adjust(TENANT, PERIOD, 3L, 100L);

        verifyAdjustment("-97");
        verifyAdjustment("97");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0L, 2L})
    void reportsRepliesWithoutSuccessAsUnapplied(Long result) {
        scriptReturns(result);

        assertThatThrownBy(() -> limiter.adjust(TENANT, PERIOD, 100L, 3L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("配额桶");
        verifyAdjustment("-97");
    }

    private void scriptReturns(Long result) {
        when(redis.execute(ArgumentMatchers.<RedisScript<Long>>any(),
                eq(QuotaScript.keys(TENANT, PERIOD)),
                eq(QuotaKeys.FIELD_TOKENS), eq(QuotaKeys.FIELD_REQUESTS), anyString()))
                .thenReturn(result);
    }

    private void verifyAdjustment(String delta) {
        verify(redis).execute(ArgumentMatchers.<RedisScript<Long>>argThat(script ->
                        Long.class.equals(script.getResultType())
                                && QuotaScript.ADJUST_SCRIPT.equals(script.getScriptAsString())),
                eq(QuotaScript.keys(TENANT, PERIOD)),
                eq(QuotaKeys.FIELD_TOKENS), eq(QuotaKeys.FIELD_REQUESTS), eq(delta));
    }
}
