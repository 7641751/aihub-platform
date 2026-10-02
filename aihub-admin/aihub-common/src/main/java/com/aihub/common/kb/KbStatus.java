package com.aihub.common.kb;

/**
 * {@code kb_document.status} 的取值集合（M5 决策 D1/D4）。
 *
 * <p>取值刻意是**裸字符串常量**而不是枚举：这张表的 {@code status} 列是 {@code VARCHAR(16)}，
 * 读它的人（运维、SQL 客户端、Task 8 的文档与检索侧）拿到的就是裸字符串；一旦用枚举，落库形态就会
 * 被 {@code @EnumValue} / ORM 的默认 {@code name()} 策略在无声中改变。这与
 * {@code com.aihub.service.audit.AuditAction} 的先例一致 —— 承载物是裸字符串，常量也照裸字符串写，
 * 让两侧引用同一批字面量而不引入 ORM 语义。
 *
 * <p>状态流转（M5 流水线）：
 * {@link #PENDING}（已登记，未处理）→ {@link #PARSING}（解析中）→ {@link #EMBEDDING}（嵌入中）
 * → {@link #READY}（**所有**分段已嵌入）或 {@link #FAILED}（终态，失败原因见 {@code error_msg}）。
 * {@code READY} 的判据是 {@code kb_chunk} 里本 doc 的**所有**行 {@code embedded_at} 非空，
 * 而不是 {@code chunk_count}（那是期望值）。
 *
 * <p>{@code status} 列是 {@code VARCHAR(16)}：新增常量时必须先确认长度放得下 ——
 * 超长在严格模式下是硬失败（好事），在非严格模式下会被**静默截断**成另一个状态（坏事）。
 */
public final class KbStatus {

    /** 已登记，尚未开始处理（V1 的列默认值也是它）。 */
    public static final String PENDING = "PENDING";
    /** 原件解析中（抽取文本 / 切分）。 */
    public static final String PARSING = "PARSING";
    /** 分段已落库，正在写入向量库。 */
    public static final String EMBEDDING = "EMBEDDING";
    /** 终态：本 doc 的所有分段都已嵌入。 */
    public static final String READY = "READY";
    /** 终态：处理失败，原因在 {@code kb_document.error_msg}。 */
    public static final String FAILED = "FAILED";

    private KbStatus() {
    }
}
