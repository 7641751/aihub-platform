package com.aihub.service.log;

import com.aihub.dao.entity.AuditLogEntity;
import com.aihub.dao.mapper.AuditLogMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 审计日志的**运营查询**（Task 11）：按 {@code tenant_id} + 时间范围分页。
 *
 * <p><b>审计是 §12 的 M4 交付物之一，只有写入路径不算交付</b>（评审点名）—— Task 7/8/9/10 写了审计行，
 * 本服务把「谁能读回它们」补齐。
 *
 * <p><b>为什么必须显式 {@code tenantId} + 时间范围</b>（{@code docs/CONVENTIONS.md} §10 R3.1）：
 * {@code audit_log} 的索引是 {@code (tenant_id, created_at)}，无界查询会退化成全表扫描。因此「缺参即
 * 400」（校验在控制器）。注意：{@code tenant_id} 为 SQL {@code NULL} 的审计行（全局资源
 * {@code channel}/{@code model_route} 的写）**不会**被按租户的运营查询返回 —— 这与「运营查询按租户
 * 归集」的语义一致（它们本来就不属于任何租户，见 §10 R1）。
 *
 * <p><b>时间绑定是 {@code LocalDateTime}(UTC)</b>，理由与 {@link RequestLogQueryService} 完全一致
 * （{@code created_at} 是 UTC 墙钟的 {@code datetime(3)}；{@code Instant}/{@code Timestamp} 会按连接
 * 时区折算，在非 UTC 连接上静默选错窗口）。见 {@code docs/CONVENTIONS.md} §7。
 *
 * <p><b>分页机制 = 显式的有界 {@code LIMIT/OFFSET}（⚠️ 已登记偏差）</b>：计划要求的
 * {@code PaginationInnerInterceptor} 在 MyBatis-Plus 3.5.9+ 属于独立构件
 * {@code com.baomidou:mybatis-plus-jsqlparser}（本仓库类路径上没有，启用它必须改 {@code pom.xml}，
 * 而派发铁律不许动 {@code pom.xml}）。因此这里改用 {@code selectCount} + {@code selectList(…ORDER BY,
 * LIMIT, OFFSET)}：{@code LIMIT/OFFSET} 是**控制器已校验过的整数**（{@code size} ∈ [1,200]、
 * {@code page} ≥ 0），不含用户字符串、没有注入面。理由与 {@link RequestLogQueryService} 完全一致。
 *
 * <p><b>视图只做只读回显</b>：{@code detail} 是写入侧保证过的**非敏感**变更摘要（Task 7/8/9/10 各自
 * 保证），本服务不重新脱敏、也不重新解释它的内容 —— 它只把那一格原样读出来。视图里**没有**明文、
 * {@code key_hash}、渠道密文这类字段（它们在 {@code audit_log} 里本来就不存在）。
 *
 * <p>{@link AuditLogView} 是**本服务内的嵌套 record**（不额外建文件）。
 */
@Service
public class AuditQueryService {

    private final AuditLogMapper auditLogMapper;

    public AuditQueryService(AuditLogMapper auditLogMapper) {
        this.auditLogMapper = auditLogMapper;
    }

    /** 审计的只读视图：与 {@link AuditLogEntity} 的列一一对应，**不含**任何密钥材料。 */
    public record AuditLogView(Long id, Long tenantId, String actorType, String actor, String action,
                               String targetType, String targetId, String detail, String requestId,
                               LocalDateTime createdAt) {
    }

    public Page<AuditLogView> page(long tenantId, Instant from, Instant to, int page, int size) {
        long total = auditLogMapper.selectCount(new LambdaQueryWrapper<AuditLogEntity>()
                .eq(AuditLogEntity::getTenantId, tenantId)
                // 显式 UTC 墙钟，不依赖连接时区、不依赖 JVM 默认时区（CONVENTIONS §7）。
                .ge(AuditLogEntity::getCreatedAt, LocalDateTime.ofInstant(from, ZoneOffset.UTC))
                .le(AuditLogEntity::getCreatedAt, LocalDateTime.ofInstant(to, ZoneOffset.UTC)));
        // 有界分页：LIMIT/OFFSET 是自算的整数（无注入面），等价于分页拦截器注入的那一段 SQL。
        long offset = (long) page * size;
        List<AuditLogEntity> rows = auditLogMapper.selectList(new LambdaQueryWrapper<AuditLogEntity>()
                .eq(AuditLogEntity::getTenantId, tenantId)
                .ge(AuditLogEntity::getCreatedAt, LocalDateTime.ofInstant(from, ZoneOffset.UTC))
                .le(AuditLogEntity::getCreatedAt, LocalDateTime.ofInstant(to, ZoneOffset.UTC))
                .orderByDesc(AuditLogEntity::getCreatedAt)
                .orderByDesc(AuditLogEntity::getId)
                .last("LIMIT " + size + " OFFSET " + offset));

        Page<AuditLogView> result = new Page<>(page, size, total);
        result.setRecords(rows.stream().map(AuditQueryService::view).toList());
        return result;
    }

    private static AuditLogView view(AuditLogEntity entity) {
        return new AuditLogView(entity.getId(), entity.getTenantId(), entity.getActorType(), entity.getActor(),
                entity.getAction(), entity.getTargetType(), entity.getTargetId(), entity.getDetail(),
                entity.getRequestId(), entity.getCreatedAt());
    }
}
