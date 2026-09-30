package com.aihub.service.channel;

import com.aihub.common.api.ErrorCode;
import com.aihub.common.config.ChannelDescriptor;
import com.aihub.common.exception.BizException;
import com.aihub.dao.entity.ChannelEntity;
import com.aihub.dao.mapper.ChannelMapper;
import com.aihub.service.audit.AuditAction;
import com.aihub.service.audit.AuditService;
import com.aihub.service.config.ConfigChangePublisher;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 渠道的控制面写路径（Task 8）：CRUD + 加密写入 + 主密钥轮换。
 *
 * <p><b>明文密钥的生命周期只有一次函数调用</b>：{@code apiKey} 从请求体进来、立刻交给
 * {@link ChannelKeyService#encrypt}，落库的只有自描述密文 {@code v{n}:{base64}}。
 * 明文不进审计（{@link #audit} 的 detail 里只有名字/提供方/版本号这类非敏感字段）、
 * 不进日志、不进任何响应 DTO（{@link View} 连 {@code apiKeyCipher} 字段都没有）。
 *
 * <p><b>每次写都在同一个事务里做三件事</b>：业务写 + 审计（{@link AuditService} 刻意不加
 * {@code REQUIRES_NEW}，因此加入本方法的事务）+ {@link ConfigChangePublisher#publishAfterCommit(String)}
 * （注册 after-commit 钩子，**提交之后**才抬水位并广播）。
 * 回滚时三者一起作废 —— 这正是 {@code ChannelAdminIntegrationTest} 的回滚用例钉住的不变量。
 *
 * <p><b>{@code models_json} 只做展示性校验</b>（D14）：非法 JSON 直接 400 {@code INVALID_PARAM}，
 * 合法则**规范化**（{@code JsonNode.toString()}）后落库。它**不参与路由** —— M3 的路由只用
 * {@code model_route} 表（{@code ChannelEntity} 的类注释）。
 *
 * <p><b>审计的 {@code tenant_id} 传 {@code null}</b>：{@code channel} 在本 schema 里不是租户级资源
 * （V1 的建表没有 {@code tenant_id} 列），因此「影响了哪个租户」这个维度**不存在** ——
 * 传 {@code null}（SQL NULL）而不是拿操作者的租户去冒充它。操作者记在 {@code actor} 列里。
 */
@Service
public class ChannelAdminService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String TARGET_TYPE = "CHANNEL";

    /** 未提供时的默认值，与 V1 的列默认值一致（显式写出来，View 就不必依赖库的默认值）。 */
    private static final int DEFAULT_WEIGHT = 100;
    private static final int DEFAULT_PRIORITY = 0;
    private static final int DEFAULT_TIMEOUT_MS = 60_000;

    private final ChannelMapper channelMapper;
    private final ChannelKeyService keyService;
    private final AuditService auditService;
    private final ConfigChangePublisher publisher;

    public ChannelAdminService(ChannelMapper channelMapper, ChannelKeyService keyService,
                               AuditService auditService, ConfigChangePublisher publisher) {
        this.channelMapper = channelMapper;
        this.keyService = keyService;
        this.auditService = auditService;
        this.publisher = publisher;
    }

    /**
     * 渠道写请求。更新语义下 {@code null} 字段表示「不修改」；创建语义下除可选项外都必须给出。
     *
     * @param apiKey 明文渠道密钥。**只在这一次调用里存在**：除加密入口外不落库、不落审计、不进日志
     */
    public record Write(String name, String provider, String baseUrl, String apiKey, String modelsJson,
                        Integer weight, Integer priority, Integer timeoutMs, String status) {
    }

    /**
     * 渠道的展示视图。**刻意没有 {@code apiKeyCipher} 字段**：任何 GET/写响应的 DTO 都不含密文，
     * 密钥材料的存在性只用 {@code hasKey} 这一位表示，版本只用 {@code keyVersion} 表示。
     */
    public record View(Long id, String name, String provider, String baseUrl, int keyVersion, boolean hasKey,
                       String modelsJson, int weight, int priority, int timeoutMs, String status) {
    }

    @Transactional
    public View create(Write write, AuditService.Actor actor) {
        String name = requireText(write.name(), "name");
        String provider = requireText(write.provider(), "provider");
        String baseUrl = requireText(write.baseUrl(), "baseUrl");
        if (write.apiKey() == null || write.apiKey().isBlank()) {
            throw new BizException(ErrorCode.INVALID_PARAM, "apiKey 不能为空");
        }

        int keyVersion = keyService.currentKeyVersion();
        ChannelEntity entity = new ChannelEntity();
        entity.setName(name);
        entity.setProvider(provider);
        entity.setBaseUrl(baseUrl);
        entity.setApiKeyCipher(keyService.encrypt(write.apiKey()));
        entity.setKeyVersion(keyVersion);
        entity.setModelsJson(normalizeModelsJson(write.modelsJson()));
        entity.setWeight(write.weight() == null ? DEFAULT_WEIGHT : write.weight());
        entity.setPriority(write.priority() == null ? DEFAULT_PRIORITY : write.priority());
        entity.setTimeoutMs(write.timeoutMs() == null ? DEFAULT_TIMEOUT_MS : write.timeoutMs());
        entity.setStatus(write.status() == null || write.status().isBlank()
                ? ChannelDescriptor.STATUS_ACTIVE : write.status().strip());
        channelMapper.insert(entity);

        audit(actor, AuditAction.CHANNEL_CREATE, entity,
                Map.of("name", entity.getName(), "provider", entity.getProvider(), "keyVersion", keyVersion));
        publisher.publishAfterCommit("channel.create");
        return view(entity);
    }

    public List<View> list() {
        return channelMapper.selectList(new LambdaQueryWrapper<ChannelEntity>()
                        .orderByAsc(ChannelEntity::getId)).stream()
                .map(ChannelAdminService::view)
                .toList();
    }

    public View get(long id) {
        return view(requireChannel(id));
    }

    @Transactional
    public View update(long id, Write write, AuditService.Actor actor) {
        ChannelEntity entity = requireChannel(id);
        boolean keyReplaced = false;

        if (write.name() != null) {
            entity.setName(requireText(write.name(), "name"));
        }
        if (write.provider() != null) {
            entity.setProvider(requireText(write.provider(), "provider"));
        }
        if (write.baseUrl() != null) {
            entity.setBaseUrl(requireText(write.baseUrl(), "baseUrl"));
        }
        if (write.apiKey() != null && !write.apiKey().isBlank()) {
            // 换密钥：明文在这里被消费掉，落库的只有新密文与新版本号。
            entity.setApiKeyCipher(keyService.encrypt(write.apiKey()));
            entity.setKeyVersion(keyService.currentKeyVersion());
            keyReplaced = true;
        }
        if (write.modelsJson() != null) {
            entity.setModelsJson(normalizeModelsJson(write.modelsJson()));
        }
        if (write.weight() != null) {
            entity.setWeight(write.weight());
        }
        if (write.priority() != null) {
            entity.setPriority(write.priority());
        }
        if (write.timeoutMs() != null) {
            entity.setTimeoutMs(write.timeoutMs());
        }
        if (write.status() != null) {
            entity.setStatus(requireText(write.status(), "status"));
        }
        channelMapper.updateById(entity);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("name", entity.getName());
        detail.put("keyReplaced", keyReplaced);
        detail.put("keyVersion", entity.getKeyVersion());
        audit(actor, AuditAction.CHANNEL_UPDATE, entity, detail);
        publisher.publishAfterCommit("channel.update");
        return view(entity);
    }

    @Transactional
    public void delete(long id, AuditService.Actor actor) {
        ChannelEntity entity = requireChannel(id);
        channelMapper.deleteById(id);
        audit(actor, AuditAction.CHANNEL_DELETE, entity, Map.of("name", entity.getName()));
        publisher.publishAfterCommit("channel.delete");
    }

    /**
     * 主密钥轮换：把这条渠道的**已有明文**用**当前**主密钥重新加密，{@code key_version} 跟着前进。
     *
     * <p>实现要点：先把落库密文解回明文（{@link ChannelKeyService#decrypt} 只在主密钥表里还有旧版本时
     * 成功），再用当前版本加密 —— 因此「轮换」= 换**加密版本**，不是换上游密钥（上游密钥由运维提供，
     * 本平台不生成）。解不开的密文**响亮地失败**（{@code CONFIGURATION_ERROR}），绝不写一条
     * 同样解不开的新密文。
     */
    @Transactional
    public View rotateKey(long id, AuditService.Actor actor) {
        ChannelEntity entity = requireChannel(id);
        String plaintext = keyService.decrypt(entity.getApiKeyCipher())
                .orElseThrow(() -> new BizException(ErrorCode.CONFIGURATION_ERROR,
                        "渠道 id=" + id + " 的密文无法用当前主密钥解开，拒绝轮换（不写一条同样解不开的新密文）"));

        int keyVersion = keyService.currentKeyVersion();
        entity.setApiKeyCipher(keyService.encrypt(plaintext));
        entity.setKeyVersion(keyVersion);
        channelMapper.updateById(entity);

        audit(actor, AuditAction.CHANNEL_ROTATE_KEY, entity,
                Map.of("name", entity.getName(), "keyVersion", keyVersion));
        publisher.publishAfterCommit("channel.rotate-key");
        return view(entity);
    }

    // ---------------------------------------------------------------- 内部

    /** 审计写在**调用方事务**里（{@link AuditService#record} 不加 {@code REQUIRES_NEW}）。 */
    private void audit(AuditService.Actor actor, String action, ChannelEntity entity, Map<String, Object> detail) {
        auditService.record(null, actor, action, TARGET_TYPE, String.valueOf(entity.getId()), detail);
    }

    private ChannelEntity requireChannel(long id) {
        ChannelEntity entity = channelMapper.selectById(id);
        if (entity == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "渠道不存在: id=" + id);
        }
        return entity;
    }

    /**
     * 展示性 JSON 校验（D14）：{@code null}/空白当作「未提供」落 SQL NULL；非空必须是合法 JSON，
     * 否则 400 {@code INVALID_PARAM}。落库的是**规范化**后的文本（{@code JsonNode.toString()}），
     * 这样库里那一格与校验通过的那个 JSON 树逐字对应。
     *
     * <p>它**不参与路由**：路由只用 {@code model_route} 表。
     */
    private static String normalizeModelsJson(String modelsJson) {
        if (modelsJson == null || modelsJson.isBlank()) {
            return null;
        }
        JsonNode tree;
        try {
            tree = MAPPER.readTree(modelsJson);
        } catch (JsonProcessingException e) {
            // 异常消息里**不带**原文：它可能很长，而且没有必要（400 的语义已经足够）。
            throw new BizException(ErrorCode.INVALID_PARAM, "modelsJson 不是合法 JSON");
        }
        if (tree == null) {
            throw new BizException(ErrorCode.INVALID_PARAM, "modelsJson 不是合法 JSON");
        }
        return tree.toString();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BizException(ErrorCode.INVALID_PARAM, field + " 不能为空");
        }
        return value.strip();
    }

    private static View view(ChannelEntity entity) {
        String cipher = entity.getApiKeyCipher();
        return new View(entity.getId(), entity.getName(), entity.getProvider(), entity.getBaseUrl(),
                entity.getKeyVersion() == null ? 0 : entity.getKeyVersion(),
                cipher != null && !cipher.isBlank(),
                entity.getModelsJson(),
                entity.getWeight() == null ? 0 : entity.getWeight(),
                entity.getPriority() == null ? 0 : entity.getPriority(),
                entity.getTimeoutMs() == null ? 0 : entity.getTimeoutMs(),
                entity.getStatus());
    }
}
