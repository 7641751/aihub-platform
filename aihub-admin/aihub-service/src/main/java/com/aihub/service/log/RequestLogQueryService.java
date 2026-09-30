package com.aihub.service.log;

import com.aihub.dao.entity.RequestLogEntity;
import com.aihub.dao.mapper.RequestLogMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 请求日志的**运营查询**（Task 11）：按 {@code tenant_id} + 时间范围 + 可选维度分页。
 *
 * <p><b>为什么必须显式 {@code tenantId} + 时间范围</b>（{@code docs/CONVENTIONS.md} §10 R3.1）：
 * {@code request_log} 是按月分区的大表，索引是 {@code (tenant_id, created_at)}；没有这两个条件的分页
 * 会退化成**全表扫描**。所以「缺参即 400」不是风格，而是防无界扫描的入口（校验在控制器，本服务假定
 * 入参已经合法）。
 *
 * <p><b>时间绑定是 {@code LocalDateTime}(UTC)，绝不是 {@code Instant}/{@code Timestamp}（CONVENTIONS §7，
 * Global Constraints 明文点名 Task 11）</b>：{@code created_at} 是**无时区**的 {@code datetime(3)}，
 * 存的是 UTC 墙上时间。{@code LocalDateTime.ofInstant(instant, ZoneOffset.UTC)} 把它换成同一基准的墙钟
 * 去比较，结果与 JDBC 连接时区、与 JVM 默认时区都无关；而 {@code Timestamp}/{@code Instant} 会由驱动
 * 按**连接时区**折算，在非 UTC 连接上会把窗口整体推走、静默选中另一个窗口的行。
 *
 * <p><b>可选维度（{@code apiKeyId}/{@code channelId}）缺省 = 不过滤</b>，由**条件式构造**实现：
 * 缺省时**不加**任何条件。绝不把 {@code null} 交给 {@code eq(...)} —— 那会生成 {@code col = NULL}、
 * 恒为 UNKNOWN、**静默返回 0 行**（CONVENTIONS §7）。
 *
 * <p><b>分页真的会加 {@code LIMIT}（⚠️ 已登记偏差，见类尾注释）</b>：计划 Interfaces 第 1 条要求用
 * {@code PaginationInnerInterceptor} + {@code selectPage}，但那个类在 MyBatis-Plus 3.5.9+ 已拆到
 * **独立构件** {@code com.baomidou:mybatis-plus-jsqlparser}，而本仓库的类路径上没有它
 * （{@code mybatis-plus-spring-boot3-starter} 不传递该构件）—— 启用它必须改 {@code pom.xml}，
 * 而派发铁律明令**不许动 {@code pom.xml}**。因此这里改用**等效**的有界分页：
 * {@code selectCount}(条件) + {@code selectList}(条件 + {@code ORDER BY} + 显式 {@code LIMIT/OFFSET})。
 * {@code LIMIT}/{@code OFFSET} 的值是**已由控制器校验过的整数**（{@code size} ∈ [1,200]、{@code page} ≥ 0），
 * 不含任何用户字符串，因此这里的内联拼接**没有注入面**（{@code last(...)} 只接受我们自己算出来的数字）。
 *
 * <p>排序固定 {@code created_at DESC}（没有 ORDER BY 的分页没有意义），并以 {@code id DESC} 作为
 * 并列时的确定性次级键。
 *
 * <p>{@link RequestLogView} 刻意是**本服务内的嵌套 record**（不额外建文件）：它是只读投影，字段与
 * {@link RequestLogEntity} 的**非敏感**列一一对应 —— 没有明文、没有 {@code key_hash}、没有渠道密文。
 */
@Service
public class RequestLogQueryService {

    private final RequestLogMapper requestLogMapper;

    public RequestLogQueryService(RequestLogMapper requestLogMapper) {
        this.requestLogMapper = requestLogMapper;
    }

    /**
     * 日志的只读视图。**不含任何密钥材料**（{@code request_log} 本来就没有这些列）：只有计费与排障需要的
     * 维度与计量字段。
     */
    public record RequestLogView(Long id, String requestId, Long tenantId, Long apiKeyId, Long channelId,
                                 String model, Integer promptTokens, Integer completionTokens, Integer totalTokens,
                                 Integer latencyMs, Integer ttftMs, String status, String errorCode,
                                 LocalDateTime createdAt) {
    }

    public Page<RequestLogView> page(long tenantId, Instant from, Instant to, Long apiKeyId, Long channelId,
                                     int page, int size) {
        long total = requestLogMapper.selectCount(conditions(tenantId, from, to, apiKeyId, channelId));
        // 有界分页：LIMIT/OFFSET 是自算的整数（无注入面），等价于分页拦截器注入的那一段 SQL。
        long offset = (long) page * size;
        List<RequestLogEntity> rows = requestLogMapper.selectList(conditions(tenantId, from, to, apiKeyId, channelId)
                .orderByDesc(RequestLogEntity::getCreatedAt)
                .orderByDesc(RequestLogEntity::getId)
                .last("LIMIT " + size + " OFFSET " + offset));
        Page<RequestLogView> result = new Page<>(page, size, total);
        result.setRecords(rows.stream().map(RequestLogQueryService::view).toList());
        return result;
    }

    /** 只放**过滤条件**（不放 ORDER BY / LIMIT）：计数与取页各用一份新的 wrapper，避免把分页段带进 COUNT。 */
    private static LambdaQueryWrapper<RequestLogEntity> conditions(long tenantId, Instant from, Instant to,
                                                                   Long apiKeyId, Long channelId) {
        LambdaQueryWrapper<RequestLogEntity> wrapper = new LambdaQueryWrapper<RequestLogEntity>()
                .eq(RequestLogEntity::getTenantId, tenantId)
                // 基准写在代码里：显式 UTC 墙钟，不依赖连接时区，也不依赖 JVM 默认时区（CONVENTIONS §7）。
                .ge(RequestLogEntity::getCreatedAt, LocalDateTime.ofInstant(from, ZoneOffset.UTC))
                .le(RequestLogEntity::getCreatedAt, LocalDateTime.ofInstant(to, ZoneOffset.UTC));
        // 可选维度：缺省 = 不过滤（条件式构造），绝不 eq(col, null)。
        if (apiKeyId != null) {
            wrapper.eq(RequestLogEntity::getApiKeyId, apiKeyId);
        }
        if (channelId != null) {
            wrapper.eq(RequestLogEntity::getChannelId, channelId);
        }
        return wrapper;
    }

    private static RequestLogView view(RequestLogEntity entity) {
        return new RequestLogView(entity.getId(), entity.getRequestId(), entity.getTenantId(), entity.getApiKeyId(),
                entity.getChannelId(), entity.getModel(), entity.getPromptTokens(), entity.getCompletionTokens(),
                entity.getTotalTokens(), entity.getLatencyMs(), entity.getTtftMs(), entity.getStatus(),
                entity.getErrorCode(), entity.getCreatedAt());
    }
}
