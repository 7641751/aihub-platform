package com.aihub.admin.kb;

import com.aihub.service.kb.KbChunker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M5 Task 4 的**零上下文单测**（不启 Spring）：{@link KbChunker} 的切分边界。
 *
 * <p><b>为什么放在 {@code aihub-web} 而不是 {@code aihub-service}</b>：{@code aihub-service}
 * 模块**没有 {@code src/test}、{@code pom.xml} 里也没有任何测试依赖**（实测）⇒ 计划原文写的
 * {@code aihub-service/src/test/...} 编译不过。放这里（纯 JUnit + AssertJ，**不启 Spring**）
 * 既不占上下文预算，也**不许**为此改任何 {@code pom.xml}。
 *
 * <p><b>⚠️ 与计划原文的一处偏差（算术证明）</b>：计划写
 * {@code chunk("x".repeat(2500), 800, 100)).hasSize(3)} 并注 "800 + 700 + 700（带 100 重叠）"。
 * 但那在算术上**不可能**：3 段、每段 ≤ 800、相邻重叠 100 ⇒ 最多覆盖
 * {@code 3*800 − 2*100 = 2200 < 2500}，会**静默丢掉 300 个字符**。无缝隙滑窗的正确下界是
 * {@code ceil((2500 − 100) / (800 − 100)) = ceil(2400/700) = 4}。本实现按"无缝隙 + 相邻恰好重叠
 * {@code overlap} 个字符"做，断言因此如实写 **4**（并额外断言"拼接去重后能逐字还原全文"，把
 * "丢字符"变成可证伪的失败）。
 */
class KbChunkerTest {

    @Test
    void emptyOrBlankTextYieldsNoChunks() {
        assertThat(KbChunker.chunk(null, 800, 100)).as("null ⇒ 0 段").isEmpty();
        assertThat(KbChunker.chunk("", 800, 100)).isEmpty();
        assertThat(KbChunker.chunk("   \n\t ", 800, 100))
                .as("全空白 ⇒ 0 段（D14：Task 4 据此判 FAILED(\"无可提取文本\")）")
                .isEmpty();
    }

    @Test
    void shortTextIsASingleChunk() {
        assertThat(KbChunker.chunk("abc", 800, 100)).containsExactly("abc");
    }

    @Test
    void longTextIsSplitIntoOverlappingWindowsThatCoverEveryCharacter() {
        String text = "x".repeat(2500);
        List<String> chunks = KbChunker.chunk(text, 800, 100);

        assertThat(chunks).as("无缝隙滑窗：ceil((2500-100)/(800-100)) = 4 段（计划原文的 3 会丢 300 字符）")
                .hasSize(4);
        assertThat(chunks).allSatisfy(s -> assertThat(s.length()).isLessThanOrEqualTo(800));
        assertThat(chunks).extracting(String::length).containsExactly(800, 800, 800, 400);
        // 相邻段**恰好**重叠 overlap 个字符、且不留缝隙 ⇒ 用"丢掉每段前 overlap 个字符再拼接"必须还原全文。
        // 这条断言同时证伪"步长错""重叠错""丢了尾巴"三种变异。
        assertThat(reconstruct(chunks, 100)).as("拼接去重后必须逐字还原全文，不许丢任何字符").isEqualTo(text);
    }

    @Test
    void unicodeTextKeepsWindowsWithinTheCharBudget() {
        assertThat(KbChunker.chunk("中".repeat(1000), 800, 100))
                .allSatisfy(s -> assertThat(s.length()).isLessThanOrEqualTo(800));
    }

    @Test
    void anOverlapNotSmallerThanTheWindowIsRejected() {
        assertThatThrownBy(() -> KbChunker.chunk("abc", 100, 100))
                .as("重叠必须小于窗口，否则死循环").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KbChunker.chunk("abc", 100, 200))
                .as("重叠大于窗口同样非法").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KbChunker.chunk("abc", 0, 0))
                .as("窗口必须为正").isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 还原：{@code chunks[0] + chunks[1].substring(overlap) + chunks[2].substring(overlap) + …}
     * —— 只有当"每段（除末段）长 {@code size}、步长 {@code size-overlap}、末段覆盖到文末"三条同时成立时
     * 才等于原文本。
     */
    private static String reconstruct(List<String> chunks, int overlap) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            out.append(i == 0 ? chunk : chunk.substring(overlap));
        }
        return out.toString();
    }
}
