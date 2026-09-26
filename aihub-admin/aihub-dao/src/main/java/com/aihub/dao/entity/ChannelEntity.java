package com.aihub.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 对应 Flyway V1 的 {@code channel} 表。
 *
 * <p>{@code apiKeyCipher} 是 **AES-GCM 密文**（{@code v{n}:{base64}}）：明文渠道密钥从不入库
 * （设计文档 §6.1）。{@code keyVersion} 是 admin 侧记录的版本号，与密文里的标签一致。
 *
 * <p>{@code modelsJson} 用 {@link String} 映射 {@code JSON} 列：M3 的路由只用 {@code model_route}
 * 表，不解析这个列。字段名到列名的下划线映射由 MyBatis-Plus 的默认策略完成，与既有
 * {@code ApiKeyEntity} 的写法一致（无 Lombok、显式 getter/setter）。
 *
 * <p><b>列与字段一一对应</b>（V1 第 45-61 行）：{@code id / name / provider / base_url /
 * api_key_cipher / key_version / models_json / weight / priority / timeout_ms / status}。
 * 刻意**不**映射 {@code created_at} / {@code updated_at}：M3 不读它们，多映射两个字段只会让
 * 「INSERT 时到底写了什么」变得难以推理（而且这两列的默认值在库里已经是对的）。
 * 这一条对 {@code ModelRouteEntity} / {@code RateLimitPolicyEntity} 同样成立。
 */
@TableName("channel")
public class ChannelEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String provider;
    private String baseUrl;
    private String apiKeyCipher;
    private Integer keyVersion;
    private String modelsJson;
    private Integer weight;
    private Integer priority;
    private Integer timeoutMs;
    private String status;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKeyCipher() {
        return apiKeyCipher;
    }

    public void setApiKeyCipher(String apiKeyCipher) {
        this.apiKeyCipher = apiKeyCipher;
    }

    public Integer getKeyVersion() {
        return keyVersion;
    }

    public void setKeyVersion(Integer keyVersion) {
        this.keyVersion = keyVersion;
    }

    public String getModelsJson() {
        return modelsJson;
    }

    public void setModelsJson(String modelsJson) {
        this.modelsJson = modelsJson;
    }

    public Integer getWeight() {
        return weight;
    }

    public void setWeight(Integer weight) {
        this.weight = weight;
    }

    public Integer getPriority() {
        return priority;
    }

    public void setPriority(Integer priority) {
        this.priority = priority;
    }

    public Integer getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(Integer timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
