package com.aihub.admin.quota;

import com.aihub.common.api.ErrorCode;
import com.aihub.common.exception.BizException;
import com.aihub.common.quota.QuotaKeys;
import com.aihub.dao.entity.QuotaEntity;
import com.aihub.dao.mapper.QuotaMapper;
import com.aihub.service.quota.QuotaUsageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link QuotaUsageService} 的**零上下文单测**（纯 JUnit + Mockito，**不启 Spring**）。
 *
 * <p><b>为什么这些断言必须在这里而不是集成测试</b>：
 * <ol>
 *   <li><b>Redis 不可用</b>与<b>桶值畸形</b>这两条错误路径，在共享上下文的集成测试里造不出来 ——
 *       把 Redis 指向死端口要改属性 ⇒ 会 fork 第八个 Spring 上下文，违反
 *       {@code docs/CONVENTIONS.md} §8 的 7 上下文预算；</li>
 *   <li>纯算术（{@code remaining} 的 {@code max(0, …)} 与 {@code -1 = 不限}）在这里可以逐边界钉，
 *       不必为了一个减法和真容器打交道；</li>
 *   <li>本类**不占**上下文预算，也不写任何共享状态。</li>
 * </ol>
 *
 * <p><b>只读性在这里也钉一遍</b>：断言 {@code QuotaMapper} 的写方法一次都没被调过 ——
 * 这是 D12 红线在**单元**层的哨兵（集成层另有一条 {@code theUsageEndpointNeverTouchesTheAccounts}）。
 */
@ExtendWith(MockitoExtension.class)
class QuotaUsageServiceTest {

    private static final long TENANT = 903_101L;
    private static final String PERIOD = "202610";

    @Mock
    private QuotaMapper quotaMapper;

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private HashOperations<String, String, String> hash;

    private QuotaUsageService service;

    /** 手动装配：`redis.opsForHash()` 带泛型见证，显式桩比再引一层 {@code @Mock} 配置更清楚。 */
    private QuotaUsageService service() {
        service = new QuotaUsageService(quotaMapper, redis);
        return service;
    }

    // ---------------------------------------------------------------- 1) remaining 的边界

    /**
     * 已用量**超过**限额时 remaining 必须是 {@code 0}（不是负数）：与 {@code QuotaScript} 的
     * {@code math.max(0, limit - used)} 逐字一致 —— 两边算法一旦漂移，运维看到的"剩余"就会与
     * 判定用的数不同。
     */
    @Test
    void remainingIsClampedAtZeroWhenTheBucketExceedsTheLimit() {
        when(quotaMapper.selectOne(any())).thenReturn(quota(100L, 0L));
        stubBucket("250", "9");

        QuotaUsageService.UsageView view = service().read(TENANT, PERIOD);

        assertThat(view.tokenLimit()).isEqualTo(100L);
        assertThat(view.tokenUsed()).isEqualTo(250L);
        assertThat(view.tokenRemaining()).as("超用 ⇒ 0，绝不是负数").isZero();
    }

    /** {@code 0} = 该维不限（D15）⇒ {@code -1}；两个维度都 0 ⇒ {@code unlimited=true}。 */
    @Test
    void aZeroLimitDimensionIsUnlimitedAndReportsMinusOne() {
        when(quotaMapper.selectOne(any())).thenReturn(quota(0L, 0L));
        stubBucket("1234", "5");

        QuotaUsageService.UsageView view = service().read(TENANT, PERIOD);

        assertThat(view.tokenRemaining()).isEqualTo(-1L);
        assertThat(view.requestRemaining()).isEqualTo(-1L);
        assertThat(view.unlimited()).isTrue();
        assertThat(view.tokenUsed()).as("不限不等于不记账：桶里有值就照报").isEqualTo(1_234L);
    }

    /** 没有配额行 = 不限（D15）：与"两个限额都是 0"同一套语义。 */
    @Test
    void aMissingQuotaRowMeansUnlimited() {
        when(quotaMapper.selectOne(any())).thenReturn(null);
        stubBucket("0", "0");

        QuotaUsageService.UsageView view = service().read(TENANT, PERIOD);

        assertThat(view.unlimited()).isTrue();
        assertThat(view.tokenLimit()).isZero();
        assertThat(view.requestRemaining()).isEqualTo(-1L);
    }

    // ---------------------------------------------------------------- 2) 桶的两种"没有值"

    /** 桶不存在（两个字段都读回 null）⇒ {@code used=0} + {@code bucketMissing=true}，**不是**错误。 */
    @Test
    void anAbsentBucketYieldsZeroUsedAndAFlag() {
        when(quotaMapper.selectOne(any())).thenReturn(quota(1_000L, 0L));
        when(redis.<String, String>opsForHash()).thenReturn(hash);
        when(hash.multiGet(anyString(), anyList())).thenReturn(Arrays.asList(null, null));

        QuotaUsageService.UsageView view = service().read(TENANT, PERIOD);

        assertThat(view.tokenUsed()).isZero();
        assertThat(view.tokenRemaining()).isEqualTo(1_000L);
        assertThat(view.bucketMissing()).isTrue();
        assertThat(view.source()).isEqualTo(QuotaUsageService.SOURCE_REDIS_BUCKET);
        assertThat(view.estimate()).isTrue();
    }

    /** 空串按"没有值"处理（不是畸形）：桶被外部工具写成空串时不该把用量接口打成 500。 */
    @Test
    void aBlankFieldIsTreatedAsZeroRatherThanMalformed() {
        when(quotaMapper.selectOne(any())).thenReturn(quota(1_000L, 0L));
        stubBucket("  ", "");

        QuotaUsageService.UsageView view = service().read(TENANT, PERIOD);

        assertThat(view.tokenUsed()).isZero();
        assertThat(view.bucketMissing()).as("有一个字段存在 ⇒ 桶不算完全缺失").isFalse();
    }

    // ---------------------------------------------------------------- 3) 两条错误路径

    /**
     * Redis 不可用 ⇒ {@code INTERNAL_ERROR}(500) + 明确文案。
     *
     * <p><b>绝不返回 used=0 冒充</b>：那会让运维以为"这个租户一分钱都没花"。这条与网关侧配额的
     * fail-open（D7）**方向相反**，因为读接口没有"放行"这个概念 —— 报错才是诚实的。
     */
    @Test
    void whenRedisIsDownTheReadFailsLoudlyInsteadOfReportingZero() {
        when(quotaMapper.selectOne(any())).thenReturn(quota(1_000L, 0L));
        when(redis.<String, String>opsForHash()).thenReturn(hash);
        when(hash.multiGet(anyString(), anyList())).thenThrow(new RuntimeException("connection refused"));

        assertThatThrownBy(() -> service().read(TENANT, PERIOD))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("Redis")
                .satisfies(e -> assertThat(((BizException) e).errorCode()).isEqualTo(ErrorCode.INTERNAL_ERROR));
    }

    /**
     * 桶值是畸形字符串 ⇒ {@code INTERNAL_ERROR}（**缺陷不是降级**）：与每日对账对同一列的处理同款
     * （那边抛 {@code IllegalStateException}）。静默当 0 会把"桶被写坏了"藏起来。
     */
    @Test
    void aMalformedBucketValueIsADefectNotADegradation() {
        when(quotaMapper.selectOne(any())).thenReturn(quota(1_000L, 0L));
        stubBucket("not-a-number", "1");

        assertThatThrownBy(() -> service().read(TENANT, PERIOD))
                .isInstanceOf(BizException.class)
                .hasMessageContaining(QuotaKeys.FIELD_TOKENS)
                .satisfies(e -> assertThat(((BizException) e).errorCode()).isEqualTo(ErrorCode.INTERNAL_ERROR));
    }

    /** 周期非法 ⇒ {@code INVALID_PARAM}(400)：校验复用 {@code QuotaPeriod} 的同一份语法。 */
    @Test
    void anInvalidPeriodIsRejectedBeforeTouchingRedis() {
        assertThatThrownBy(() -> service().read(TENANT, "2026-10"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).errorCode()).isEqualTo(ErrorCode.INVALID_PARAM));

        verify(redis, never()).opsForHash();
    }

    // ---------------------------------------------------------------- 4) 只读哨兵

    /** 读用量**一个字节都不写**：配额行与桶都不能出现写调用（D12 红线）。 */
    @Test
    void theReadPathNeverWritesAnything() {
        when(quotaMapper.selectOne(any())).thenReturn(quota(1_000L, 0L));
        stubBucket("10", "1");

        service().read(TENANT, PERIOD);

        // 必须用**带类型的** matcher：MyBatis-Plus 的 insert/updateById/deleteById 各有两个重载
        // （单个实体 / 集合），光写 any() 编译器判不出用哪个（实测 "对 insert 的引用不明确"）。
        verify(quotaMapper, never()).insert(any(QuotaEntity.class));
        verify(quotaMapper, never()).updateById(any(QuotaEntity.class));
        verify(quotaMapper, never()).deleteById(any(java.io.Serializable.class));
        verify(quotaMapper, never()).update(any(QuotaEntity.class), any());
        verify(redis, never()).delete(anyString());
        verify(redis, never()).opsForValue();
    }

    // ---------------------------------------------------------------- 脚手架

    private void stubBucket(String tokens, String requests) {
        when(redis.<String, String>opsForHash()).thenReturn(hash);
        when(hash.multiGet(anyString(), anyList())).thenReturn(List.of(tokens, requests));
    }

    private static QuotaEntity quota(long tokenLimit, long requestLimit) {
        QuotaEntity entity = new QuotaEntity();
        entity.setTenantId(TENANT);
        entity.setPeriod(PERIOD);
        entity.setTokenLimit(tokenLimit);
        entity.setTokenUsed(0L);
        entity.setRequestLimit(requestLimit);
        entity.setRequestUsed(0L);
        entity.setVersion(0L);
        return entity;
    }
}
