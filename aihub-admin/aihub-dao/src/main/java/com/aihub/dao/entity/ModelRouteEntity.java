package com.aihub.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 对应 Flyway V1 的 {@code model_route} 表：一个模型 → 多条候选渠道（权重 / 优先级 / 状态）。
 *
 * <p>{@code weight} 与 {@code priority} 是**路由级**的值，取它们而不是 {@code channel} 表上的默认值：
 * 同一个渠道可以给不同模型不同的权重（灰度 / 容量），这是 {@code ModelRouteDescriptor} 的既有契约。
 *
 * <p>{@code (model_name, channel_id)} 在 V1 里有唯一键 {@code uk_model_route}，因此「同一模型 +
 * 同一渠道」不会出现两行；权重在 M3 里由 gateway 的 {@code RouteResolver} 解释。
 */
@TableName("model_route")
public class ModelRouteEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String modelName;
    private Long channelId;
    private Integer weight;
    private Integer priority;
    private String status;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public Long getChannelId() {
        return channelId;
    }

    public void setChannelId(Long channelId) {
        this.channelId = channelId;
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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
