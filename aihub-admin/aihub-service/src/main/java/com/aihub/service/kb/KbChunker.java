package com.aihub.service.kb;

import java.util.ArrayList;
import java.util.List;

/**
 * 把一份文本切成**定长、带重叠、无缝隙**的分段（M5 Task 4，纯函数）。
 *
 * <p><b>几何</b>：窗口 {@code sizeChars}、相邻重叠 {@code overlapChars} ⇒ 步长
 * {@code step = sizeChars - overlapChars}。第 i 段是闭开区间 {@code [i*step, min(i*step + sizeChars, len))}。
 * 末段一旦覆盖到文末就停止 —— 否则像 {@code len == sizeChars} 这种情形会多吐一段只含重叠区的尾巴。
 *
 * <p><b>不变量（都被用例钉住）</b>：
 * <ol>
 *   <li><b>无缝隙</b>：把每段（除首段）去掉前 {@code overlapChars} 个字符再拼接，逐字还原原文（不丢任何字符）；</li>
 *   <li><b>每段 ≤ {@code sizeChars}</b>；</li>
 *   <li><b>空 / 全空白 ⇒ 空列表</b>（{@code D14}：{@code chunkCount == 0} 是 {@code FAILED("无可提取文本")} 的出口）。</li>
 * </ol>
 *
 * <p><b>⚠️ 与计划原文的偏差</b>：计划写 {@code chunk("x".repeat(2500), 800, 100)).hasSize(3)}，
 * 但那在算术上不可能 —— 3 段 × ≤800、相邻重叠 100 最多覆盖 {@code 2200 < 2500}，会静默丢 300 字符。
 * 正确下界是 {@code ceil((2500-100)/(800-100)) = 4}。实现按"无缝隙"做，用例断言 4（并断言可逐字还原）。
 *
 * <p><b>重叠必须小于窗口</b>：{@code overlapChars >= sizeChars} 会让步长 ≤ 0 ⇒ 死循环，因此**快速失败**
 * （{@link IllegalArgumentException}）而不是悄悄挂住消费线程。
 */
public final class KbChunker {

    private KbChunker() {
    }

    /**
     * @param text        待切分文本；{@code null} / 空 / 全空白 ⇒ 空列表
     * @param sizeChars   窗口大小（字符数），必须为正
     * @param overlapChars 相邻段的重叠字符数，必须 {@code >= 0} 且 {@code < sizeChars}
     * @return 顺序排列的分段；任何一段都不会是空串
     */
    public static List<String> chunk(String text, int sizeChars, int overlapChars) {
        if (sizeChars < 1) {
            throw new IllegalArgumentException("sizeChars 必须为正：" + sizeChars);
        }
        if (overlapChars < 0) {
            throw new IllegalArgumentException("overlapChars 不能为负：" + overlapChars);
        }
        if (overlapChars >= sizeChars) {
            throw new IllegalArgumentException(
                    "重叠必须小于窗口，否则步长非正会死循环：overlap=" + overlapChars + " size=" + sizeChars);
        }
        if (text == null || text.isBlank()) {
            return List.of();
        }

        int length = text.length();
        int step = sizeChars - overlapChars;
        List<String> chunks = new ArrayList<>();
        for (int start = 0; start < length; start += step) {
            int end = Math.min(start + sizeChars, length);
            chunks.add(text.substring(start, end));
            if (end == length) {
                break;   // 末段已覆盖到文末：不再吐"只含重叠区"的多余段
            }
        }
        return chunks;
    }
}
