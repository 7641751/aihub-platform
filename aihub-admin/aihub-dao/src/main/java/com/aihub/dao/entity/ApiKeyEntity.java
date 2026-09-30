package com.aihub.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 对应 Flyway V1 的 {@code api_key} 表。明文 secret 从不入库，只存 SHA-256。
 *
 * <p><b>{@code expire_at} 用 {@link LocalDateTime} 而不是 {@code Instant} / {@code java.util.Date}：
 * {@code expire_at} 是 {@code DATETIME(3)}（不带时区），而 V1 的约定是「时间统一 {@code datetime(3)}，
 * 按 **UTC** 存储」—— 它紧挨着的 {@code created_at} / {@code updated_at} 由库的
 * {@code CURRENT_TIMESTAMP(3)} 生成，存的就是 UTC 墙上时间。用 {@code Instant} 会让驱动**按 JDBC
 * 连接时区**把瞬时折成墙上时间写进去、再按同一个连接时区折回来读（**只有连接时区解析成 LOCAL** ——
 * URL 不带 {@code serverTimezone} / {@code connectionTimeZone} —— 时用的才是 **JVM 默认时区**），
 * 于是实体往返**自洽但与兄弟列差一个连接时区偏移**（本机 Asia/Shanghai 观测到 8 小时）：裸 SQL 写入方
 * （seeder / 运维）、以及将来任何 {@code where expire_at > now()} / {@code utc_timestamp()} 的比较
 * 都落在另一个基准上。显式换算只有在写入点（{@code ApiKeyService.issue}）与读取点
 * （{@code ApiKeyService.loadFromDb}）各做一次，基准才写在代码里而不是连接的时区参数里。
 *
 * <p>跨服务的契约不受影响：{@code ApiKeyView.expireAt} 仍是 {@code Instant}（HTTP/JSON 里由
 * {@code AdminClient} 用 {@code Instant.parse} 解析，Redis 里由 {@code ApiKeyCacheCodec} 存
 * epoch 秒），换算只发生在本实体与数据库之间。
 */
@TableName("api_key")
public class ApiKeyEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String keyId;
    private Long tenantId;
    private String keyHash;
    private String name;
    private String status;
    /** {@code DATETIME(3) NULL}，**UTC 墙上时间**（见类注释）；{@code null} 表示永不过期。 */
    private LocalDateTime expireAt;
    /**
     * {@code DATETIME(3) NULL}，**UTC 墙上时间**（与 {@link #expireAt} 同基准）。
     *
     * <p><b>诚实登记（Task 9）</b>：{@code api_key.last_used_at} 列存在
     * （{@code V1__init_schema.sql:36}），但 M4 **没有任何写入方** —— 因此读出来的值**恒为
     * {@code null}**。刻意不让读路径顺手写它：那会给一个只读接口引入副作用，也会把「上次使用时间」
     * 的语义塞进一个与它无关的查询里。用 {@link LocalDateTime} 而不是 {@code Instant} 的理由与
     * {@link #expireAt} 完全一致（CONVENTIONS §7 第 1 条）。
     */
    private LocalDateTime lastUsedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getKeyId() {
        return keyId;
    }

    public void setKeyId(String keyId) {
        this.keyId = keyId;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public String getKeyHash() {
        return keyHash;
    }

    public void setKeyHash(String keyHash) {
        this.keyHash = keyHash;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getExpireAt() {
        return expireAt;
    }

    public void setExpireAt(LocalDateTime expireAt) {
        this.expireAt = expireAt;
    }

    public LocalDateTime getLastUsedAt() {
        return lastUsedAt;
    }

    public void setLastUsedAt(LocalDateTime lastUsedAt) {
        this.lastUsedAt = lastUsedAt;
    }
}
