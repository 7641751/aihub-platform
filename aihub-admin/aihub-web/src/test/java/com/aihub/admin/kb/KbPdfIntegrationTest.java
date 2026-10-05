package com.aihub.admin.kb;

import com.aihub.admin.kb.support.FakeEmbeddingUpstream;
import com.aihub.admin.kb.support.KbIntegrationTestBase;
import com.aihub.dao.entity.KbDocumentEntity;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M5 Task 7 的**线上契约**：PDF 走 PDFBox 抽文本层 —— 有文本层就正常入库，**没有**就 `FAILED("无可提取文本")`。
 *
 * <p><b>夹具是"在测试里现场生成"的，不提交任何 `.pdf`</b>（控制器 2026-10-05 订正）：这样夹具是**可读、可复核、
 * 可复现**的代码，而不是一个来路不明的二进制 blob（本项目一贯不提交非文本产物）。两份夹具：
 * <ul>
 *   <li>{@link #textPdf(int)} —— A4 页 + 若干行 HELVETICA 文本（文本层存在）；</li>
 *   <li>{@link #pdfWithoutTextLayer()} —— A4 页 + **一个矩形**、不写任何文本算子（等价于扫描件的 YAGNI 形态）。</li>
 * </ul>
 *
 * <p>复用**已有** Spring 上下文（{@code @TestPropertySource} 那把字面量与其它 kb 用例逐字相同、**不加** {@code @Import}）
 * ⇒ 判据是全量套件 {@code Tomcat started on port} 仍是 **7**。断言一律走**数据库状态**，不抢队列。
 */
@TestPropertySource(properties = {
        "aihub.console.secret=console-it-secret-0123456789abcdefghijklmn"
})
class KbPdfIntegrationTest extends KbIntegrationTestBase {

    /** 本任务专用租户（与 Task 2/3/4/5 的 920001..920004 区分）。 */
    private static final long TENANT = 920_005L;

    @BeforeEach
    @AfterEach
    void resetUpstreamAndFixtures() {
        FakeEmbeddingUpstream.reset();
        cleanKbFixture(TENANT);
    }

    @Test
    void aPdfWithATextLayerIsParsedEmbeddedAndBecomesReady() throws Exception {
        FakeEmbeddingUpstream.respondWithDeterministicVectors(8);

        long id = uploadId(TENANT, "doc.pdf", textPdf(120));

        awaitUntil(Duration.ofSeconds(90), () -> "READY".equals(statusOf(id)), id);

        assertThat(chunkRowsFor(id))
                .as("有文本层的 PDF 必须切出段（120 行 ≈ 7k 字符 ⇒ 远大于一个 800 字符的窗口）")
                .isGreaterThan(0);
        assertThat(embeddedCountFor(id)).as("每一段都要真的嵌进去").isEqualTo(chunkRowsFor(id));
    }

    @Test
    void aPdfWithoutATextLayerFailsLoudlyInsteadOfSilentlySucceeding() throws Exception {
        FakeEmbeddingUpstream.respondWithDeterministicVectors(8);

        long id = uploadId(TENANT, "scanned.pdf", pdfWithoutTextLayer());

        awaitUntil(Duration.ofSeconds(60), () -> "FAILED".equals(statusOf(id)), id);

        KbDocumentEntity row = kbDocumentMapper.selectById(id);
        assertThat(row.getErrorMsg())
                .as("必须如实说「没有文本」（扫描件的 YAGNI 出口），而不是含糊的失败")
                .contains("无可提取文本");
        assertThat(chunkRowsFor(id)).as("一段都不许留").isZero();
        assertThat(statusOf(id)).as("★ 静默 READY 是最糟的失败：绝不能出现").isNotEqualTo("READY");
        assertThat(vectorStore.countByDocId(id)).as("向量库里也不许有它").isZero();
    }

    // ---------------------------------------------------------------- 夹具（现场生成）

    /** A4 单页 + {@code lines} 行文本 —— 文本层存在。 */
    private static byte[] textPdf(int lines) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                content.newLineAtOffset(40, 780);
                for (int i = 0; i < lines; i++) {
                    content.showText("pdf line " + i + " with enough characters to fill the chunk window");
                    content.newLineAtOffset(0, -12);
                }
                content.endText();
            }
            return save(document);
        }
    }

    /** A4 单页 + 一个填充矩形，**不写任何文本算子** ⇒ 抽取结果为空 ⇒ D14 的"无可提取文本"。 */
    private static byte[] pdfWithoutTextLayer() throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.addRect(50, 50, 200, 200);
                content.fill();
            }
            return save(document);
        }
    }

    private static byte[] save(PDDocument document) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        document.save(out);
        return out.toByteArray();
    }
}
