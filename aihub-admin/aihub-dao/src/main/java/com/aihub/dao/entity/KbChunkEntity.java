package com.aihub.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 对应 Flyway V3 的 {@code kb_chunk}：文档入库流水线的**逐段进度表**（M5 决策 D1/D4）。
 *
 * <p><b>列与字段一一对应，以 V3 的 SQL 为准</b>：{@code id / doc_id / seq / text / vector_id /
 * embedded_at / created_at}。{@code (doc_id, seq)} 上唯一（{@code uk_kb_chunk_doc_seq}）⇒ 重放同一段
 * 即覆盖；{@code doc_id} 上有索引（{@code idx_kb_chunk_doc}）⇒ 按 doc 清理走前缀。
 * {@code (doc_id, seq) -> vector_id} 是「这一段已经真的写进向量库」的持久坐标。
 *
 * <p>字段名到列名的下划线映射由 MyBatis-Plus 默认策略完成
 * （{@code docId -> doc_id}、{@code vectorId -> vector_id}、{@code embeddedAt -> embedded_at}、
 * {@code createdAt -> created_at}；{@code seq} / {@code text} 同名直映射）。
 *
 * <p>{@code embeddedAt}（可为 {@code null}）与 {@code createdAt} 用 {@link LocalDateTime} 而不是
 * {@code Instant}：{@code datetime(3)} 列在应用侧显式按 **UTC** 写入（CONVENTIONS §7）。
 */
@TableName("kb_chunk")
public class KbChunkEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long docId;
    private Integer seq;
    private String text;
    private String vectorId;
    private LocalDateTime embeddedAt;
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getDocId() {
        return docId;
    }

    public void setDocId(Long docId) {
        this.docId = docId;
    }

    public Integer getSeq() {
        return seq;
    }

    public void setSeq(Integer seq) {
        this.seq = seq;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getVectorId() {
        return vectorId;
    }

    public void setVectorId(String vectorId) {
        this.vectorId = vectorId;
    }

    public LocalDateTime getEmbeddedAt() {
        return embeddedAt;
    }

    public void setEmbeddedAt(LocalDateTime embeddedAt) {
        this.embeddedAt = embeddedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
