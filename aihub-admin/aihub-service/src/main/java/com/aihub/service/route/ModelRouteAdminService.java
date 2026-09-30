package com.aihub.service.route;

import com.aihub.common.api.ErrorCode;
import com.aihub.common.exception.BizException;
import com.aihub.dao.entity.ModelRouteEntity;
import com.aihub.dao.mapper.ChannelMapper;
import com.aihub.dao.mapper.ModelRouteMapper;
import com.aihub.service.audit.AuditAction;
import com.aihub.service.audit.AuditService;
import com.aihub.service.config.ConfigChangePublisher;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 模型路由的控制面写路径（Task 10）：CRUD。{@code model_route} 是**全局资源**（表里没有
 * {@code tenant_id}），因此按 {@code docs/CONVENTIONS.md} §10 的 **R1**：读写都是平台级，
 * 任何 {@code ADMIN} 都有权；审计的 {@code tenant_id} 记 **SQL NULL**（不是 0、不是操作者的租户）。
 *
 * <p><b>每一次写都在同一个 {@code @Transactional} 里做三件事</b>：业务写 + 审计
 * （{@link AuditService#record} 刻意不加 {@code REQUIRES_NEW}，因此加入本方法的事务）+
 * {@link ConfigChangePublisher#publishAfterCommit(String)}（注册 after-commit 钩子，**提交之后**才
 * 抬水位并广播）。回滚时三者一起作废 —— 由 Task 8 的
 * {@code ChannelAdminIntegrationTest#rolledBackWritePublishesNothingAndDoesNotRaiseTheWatermark} 钉住
 * （本任务按计划只做正向）。
 *
 * <p><b>唯一键冲突必须翻译成 400</b>：{@code model_route} 上有
 * {@code uk_model_route(model_name, channel_id)}，违反时直接抛的 {@link DuplicateKeyException} 会落到
 * {@code GlobalExceptionHandler} 的兜底分支变成 **500** —— 而 {@code ErrorCode} 里没有 409，
 * 语义上正确的答案就是 **400 {@code INVALID_PARAM}**。见 {@link #insertRoute}：捕获并把底层异常翻成
 * {@link BizException}，message 可读且**不含 SQL / 约束名**。
 *
 * <p><b>悬挂路由必须被挡住</b>：{@code model_route.channel_id} **没有外键**，所以建/改前必须查
 * {@link ChannelMapper}，渠道不存在 → **404 {@code NOT_FOUND}**（否则会产生悬挂路由，快照带着它、
 * 网关静默丢掉）。见 {@link #requireChannelExists}。
 *
 * <p><b>取值校验</b>：{@code status} 只接受 {@code ACTIVE}/{@code INACTIVE}（其它 400），
 * {@code weight}/{@code priority} 必须 ≥ 0（负数 400）。状态字面量用本类的**私有常量**，
 * 不复用 {@code ChannelDescriptor}/{@link com.aihub.common.config.ModelRouteDescriptor} 上的常量
 * （那是网关侧读契约，不为写侧校验去改跨服务类型）。
 */
@Service
public class ModelRouteAdminService {

    /** {@code audit_log.target_type} 的取值（{@code AuditLogEntity} 的类注释里登记了这一档）。 */
    private static final String TARGET_TYPE = "ROUTE";

    /** 状态字面量：私有 —— 写侧校验只需要知道这两个值，不把它们放上跨服务共享类型。 */
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_INACTIVE = "INACTIVE";

    /** 未提供时的默认值，与 V1 的列默认值一致（显式写出来，summary 就不必依赖库的默认值）。 */
    private static final int DEFAULT_WEIGHT = 100;
    private static final int DEFAULT_PRIORITY = 0;

    private final ModelRouteMapper modelRouteMapper;
    private final ChannelMapper channelMapper;
    private final AuditService auditService;
    private final ConfigChangePublisher publisher;

    public ModelRouteAdminService(ModelRouteMapper modelRouteMapper, ChannelMapper channelMapper,
                                  AuditService auditService, ConfigChangePublisher publisher) {
        this.modelRouteMapper = modelRouteMapper;
        this.channelMapper = channelMapper;
        this.auditService = auditService;
        this.publisher = publisher;
    }

    /**
     * 路由写请求。更新语义下 {@code null} 字段表示「不修改」；创建语义下 {@code modelName}/{@code channelId}
     * 必填，{@code weight}/{@code priority} 缺省取 V1 默认值，{@code status} 缺省 {@code ACTIVE}。
     */
    public record RouteWrite(String modelName, Long channelId, Integer weight, Integer priority, String status) {
    }

    /** 路由的展示视图（列表与写响应共用）。 */
    public record RouteSummary(long id, String modelName, long channelId, int weight, int priority, String status) {
    }

    @Transactional
    public RouteSummary create(RouteWrite write, AuditService.Actor actor) {
        String modelName = requireText(write.modelName(), "modelName");
        if (write.channelId() == null) {
            throw new BizException(ErrorCode.INVALID_PARAM, "channelId 不能为空");
        }
        requireChannelExists(write.channelId());
        int weight = requireNonNegative(write.weight(), "weight", DEFAULT_WEIGHT);
        int priority = requireNonNegative(write.priority(), "priority", DEFAULT_PRIORITY);
        String status = write.status() == null || write.status().isBlank()
                ? STATUS_ACTIVE : requireRouteStatus(write.status());

        ModelRouteEntity entity = new ModelRouteEntity();
        entity.setModelName(modelName);
        entity.setChannelId(write.channelId());
        entity.setWeight(weight);
        entity.setPriority(priority);
        entity.setStatus(status);
        insertRoute(entity);

        audit(actor, AuditAction.ROUTE_CREATE, entity,
                Map.of("modelName", modelName, "channelId", write.channelId(), "status", status));
        publisher.publishAfterCommit("route.create");
        return summary(entity);
    }

    /** R1：全局列表 —— 任何 {@code ADMIN} 看全部行（没有租户维度可过滤）。 */
    public List<RouteSummary> list() {
        return modelRouteMapper.selectList(new LambdaQueryWrapper<ModelRouteEntity>()
                        .orderByAsc(ModelRouteEntity::getId)).stream()
                .map(ModelRouteAdminService::summary)
                .toList();
    }

    @Transactional
    public RouteSummary update(long id, RouteWrite write, AuditService.Actor actor) {
        ModelRouteEntity entity = requireRoute(id);
        if (write.modelName() != null) {
            entity.setModelName(requireText(write.modelName(), "modelName"));
        }
        if (write.channelId() != null) {
            requireChannelExists(write.channelId());
            entity.setChannelId(write.channelId());
        }
        if (write.weight() != null) {
            entity.setWeight(requireNonNegative(write.weight(), "weight", DEFAULT_WEIGHT));
        }
        if (write.priority() != null) {
            entity.setPriority(requireNonNegative(write.priority(), "priority", DEFAULT_PRIORITY));
        }
        if (write.status() != null) {
            entity.setStatus(requireRouteStatus(write.status()));
        }
        updateRoute(entity);

        audit(actor, AuditAction.ROUTE_UPDATE, entity,
                Map.of("modelName", entity.getModelName(), "channelId", entity.getChannelId(),
                        "weight", entity.getWeight(), "priority", entity.getPriority(),
                        "status", entity.getStatus()));
        publisher.publishAfterCommit("route.update");
        return summary(entity);
    }

    /** {@code DELETE /api/routes/{id}} = **真删行**（与 Task 9 的 api-keys 一致；路由没有历史行语义）。 */
    @Transactional
    public void delete(long id, AuditService.Actor actor) {
        ModelRouteEntity entity = requireRoute(id);
        modelRouteMapper.deleteById(id);
        audit(actor, AuditAction.ROUTE_DELETE, entity, Map.of("modelName", entity.getModelName()));
        publisher.publishAfterCommit("route.delete");
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 建/改路由前必须确认渠道存在（{@code model_route.channel_id} 没有外键）：不存在 → 404。
     */
    private void requireChannelExists(long channelId) {
        if (channelMapper.selectById(channelId) == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "渠道不存在: id=" + channelId);
        }
    }

    /**
     * 插入并**翻译唯一键冲突**：{@code uk_model_route(model_name, channel_id)} 违反 → 400
     * {@code INVALID_PARAM}。直接放行 {@link DuplicateKeyException} 会落到兜底分支变成 500
     * （{@code ErrorCode} 里没有 409），而 message 绝不带 SQL / 约束名（只给业务可读的两维值）。
     */
    private void insertRoute(ModelRouteEntity entity) {
        try {
            modelRouteMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.INVALID_PARAM,
                    "已存在同一模型与渠道的路由: modelName=" + entity.getModelName()
                            + ", channelId=" + entity.getChannelId());
        }
    }

    /** 更新时的同款翻译（PUT 允许改 modelName/channelId，因此也可能撞唯一键）。 */
    private void updateRoute(ModelRouteEntity entity) {
        try {
            modelRouteMapper.updateById(entity);
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.INVALID_PARAM,
                    "已存在同一模型与渠道的路由: modelName=" + entity.getModelName()
                            + ", channelId=" + entity.getChannelId());
        }
    }

    private ModelRouteEntity requireRoute(long id) {
        ModelRouteEntity entity = modelRouteMapper.selectById(id);
        if (entity == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "路由不存在: id=" + id);
        }
        return entity;
    }

    private void audit(AuditService.Actor actor, String action, ModelRouteEntity entity,
                       Map<String, Object> detail) {
        // R1：model_route 是全局资源 → 审计 tenant_id 记 SQL NULL（真正的「没有租户维度」，不是 0）。
        auditService.record(null, actor, action, TARGET_TYPE, String.valueOf(entity.getId()), detail);
    }

    private static String requireRouteStatus(String status) {
        String value = status.strip();
        if (!STATUS_ACTIVE.equals(value) && !STATUS_INACTIVE.equals(value)) {
            throw new BizException(ErrorCode.INVALID_PARAM, "status 只接受 ACTIVE/INACTIVE: " + value);
        }
        return value;
    }

    private static int requireNonNegative(Integer value, String field, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value < 0) {
            throw new BizException(ErrorCode.INVALID_PARAM, field + " 不能为负: " + value);
        }
        return value;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BizException(ErrorCode.INVALID_PARAM, field + " 不能为空");
        }
        return value.strip();
    }

    private static RouteSummary summary(ModelRouteEntity entity) {
        return new RouteSummary(entity.getId(), entity.getModelName(), entity.getChannelId(),
                entity.getWeight() == null ? 0 : entity.getWeight(),
                entity.getPriority() == null ? 0 : entity.getPriority(), entity.getStatus());
    }
}
