package com.aihub.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * 对应 Flyway V2 的 {@code config_version} 表：配置快照 {@code version} 的**单行水位**（决策 D1/D5）。
 *
 * <p>表里只有 {@code id = 1} 一行，由 V2 的 {@code INSERT} 预先写入，因此主键是
 * {@link IdType#INPUT}（不是自增）。水位只在配置写入路径上抬升
 * （{@code ConfigChangePublisher.bumpAndPublish}），用来修「删掉最新一行会让版本倒退」这个缺口。
 */
@TableName("config_version")
public class ConfigVersionEntity {

    @TableId(type = IdType.INPUT)
    private Long id;
    private Long version;
    private Instant updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
