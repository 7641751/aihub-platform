package com.aihub.service.tenant;

import com.aihub.common.api.ErrorCode;
import com.aihub.common.exception.BizException;
import com.aihub.dao.entity.TenantEntity;
import com.aihub.dao.mapper.TenantMapper;
import com.aihub.service.audit.AuditAction;
import com.aihub.service.audit.AuditService;
import com.aihub.service.config.ConfigChangePublisher;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 租户的控制面写路径（Task 8）。
 *
 * <p>与 {@code ChannelAdminService} 同一套纪律：业务写 + 审计（同一事务）+ 提交后广播失效
 * （{@link ConfigChangePublisher#publishAfterCommit(String)}，而**不是**直接调
 * {@code bumpAndPublish}）。租户行本身不含任何密钥材料，因此审计 detail 里可以放心记名字与状态。
 *
 * <p><b>审计的 {@code tenant_id} 就是被操作的租户 id</b>：{@code tenant} 表是租户的载体本身，
 * 「影响了哪个租户」有明确答案；创建时先 insert 拿到自增 id，再写审计行（同一事务）。
 */
@Service
public class TenantAdminService {

    private static final String TARGET_TYPE = "TENANT";
    private static final String DEFAULT_STATUS = "ACTIVE";

    private final TenantMapper tenantMapper;
    private final AuditService auditService;
    private final ConfigChangePublisher publisher;

    public TenantAdminService(TenantMapper tenantMapper, AuditService auditService,
                             ConfigChangePublisher publisher) {
        this.tenantMapper = tenantMapper;
        this.auditService = auditService;
        this.publisher = publisher;
    }

    /** 租户写请求；{@code null} 字段在更新语义下表示「不修改」。 */
    public record Write(String name, String status) {
    }

    /** 租户视图。 */
    public record View(Long id, String name, String status) {
    }

    @Transactional
    public View create(Write write, AuditService.Actor actor) {
        String name = requireText(write.name(), "name");
        TenantEntity entity = new TenantEntity();
        entity.setName(name);
        entity.setStatus(write.status() == null || write.status().isBlank()
                ? DEFAULT_STATUS : write.status().strip());
        tenantMapper.insert(entity);

        auditService.record(entity.getId(), actor, AuditAction.TENANT_CREATE, TARGET_TYPE,
                String.valueOf(entity.getId()), Map.of("name", entity.getName(), "status", entity.getStatus()));
        publisher.publishAfterCommit("tenant.create");
        return view(entity);
    }

    public List<View> list() {
        return tenantMapper.selectList(new LambdaQueryWrapper<TenantEntity>()
                        .orderByAsc(TenantEntity::getId)).stream()
                .map(TenantAdminService::view)
                .toList();
    }

    @Transactional
    public View update(long id, Write write, AuditService.Actor actor) {
        TenantEntity entity = tenantMapper.selectById(id);
        if (entity == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "租户不存在: id=" + id);
        }
        if (write.name() != null) {
            entity.setName(requireText(write.name(), "name"));
        }
        if (write.status() != null) {
            entity.setStatus(requireText(write.status(), "status"));
        }
        tenantMapper.updateById(entity);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("name", entity.getName());
        detail.put("status", entity.getStatus());
        auditService.record(entity.getId(), actor, AuditAction.TENANT_UPDATE, TARGET_TYPE,
                String.valueOf(entity.getId()), detail);
        publisher.publishAfterCommit("tenant.update");
        return view(entity);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BizException(ErrorCode.INVALID_PARAM, field + " 不能为空");
        }
        return value.strip();
    }

    private static View view(TenantEntity entity) {
        return new View(entity.getId(), entity.getName(), entity.getStatus());
    }
}
