package com.aihub.service.kb;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.nio.file.Path;
import java.util.Locale;

/**
 * 把原件抽成纯文本（M5 Task 4）。当前只支持 {@code md} / {@code txt}；{@code pdf} 由 **Task 7** 加进来。
 *
 * <p><b>为什么单独一个类而不是内联在消费者里</b>：抽取是唯一与"文件格式"耦合的一步，独立出来之后
 * Task 7 只需在这里加一个分支（PDFBox），消费端的流程一行都不用动。
 *
 * <p><b>未知扩展名快速失败</b>：抛 {@link IllegalArgumentException}。上传白名单（{@code md/txt}）
 * 已经在 {@code KbDocumentService} 拦过一次，走到这里还撞上未知扩展名说明是数据/装配问题，
 * 宁可把它送进 DLQ 也不要静默抽成空串（那会伪装成 {@code D14} 的"无可提取文本"）。
 */
@Component
public class KbTextExtractor {

    /**
     * @param extension 小写或混合大小写的扩展名（不含点），如 {@code md}
     * @param file      已落盘的原件路径
     * @return 抽取出的文本（可能是空串 —— 例如空文档；由调用方按 {@code D14} 判 {@code FAILED}）
     */
    public String extract(String extension, Path file) {
        String ext = extension == null ? "" : extension.toLowerCase(Locale.ROOT);
        return switch (ext) {
            case "md", "txt" -> readUtf8(file);
            case "pdf" -> readPdf(file);
            default -> throw new IllegalArgumentException(
                    "不支持的扩展名：" + (ext.isEmpty() ? "（无）" : ext) + "（当前只支持 md/txt/pdf）");
        };
    }

    private static String readUtf8(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取原件失败：" + file, e);
        }
    }

    /**
     * PDF 的**文本层**抽取（M5 Task 7，PDFBox 3.x 的 {@link Loader} + {@link PDFTextStripper}）。
     *
     * <p><b>没有文本层 ⇒ 返回空串，这是故意的</b>：交给 D14 的出口（`chunkCount == 0` ⇒
     * `FAILED("无可提取文本")`）—— 扫描件/图片型 PDF 的 YAGNI 边界就在这里，**不做 OCR**，
     * 也绝不把它当成"解析成功"。
     *
     * <p><b>解析失败一律往外抛</b>（文件损坏 / 加密 / 非 PDF）：静默返回空串会把"文件坏了"伪装成
     * D14 的"里面本来就没字"，两件事在运维上必须分得开（前者要重传，后者要 OCR 或换文件）。
     */
    private static String readPdf(Path file) {
        try (PDDocument document = Loader.loadPDF(file.toFile())) {
            return new PDFTextStripper().getText(document);
        } catch (IOException e) {
            throw new UncheckedIOException("解析 PDF 失败：" + file, e);
        }
    }
}
