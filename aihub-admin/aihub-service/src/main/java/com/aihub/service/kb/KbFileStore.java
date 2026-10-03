package com.aihub.service.kb;

import com.aihub.common.api.ErrorCode;
import com.aihub.common.exception.BizException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 原件的落盘门面（M5 决策 D1）：**原件是唯一真相源**，向量库是可重建的派生数据
 * （见 `docs/superpowers/specs/2026-09-23-aihub-platform-design.md` §6.4）。
 *
 * <p><b>路径协议</b>：{@code {root}/{tenantId}/{sha256}} —— 用内容摘要当文件名有两个好处：
 * ① 同内容天然幂等（同一个文件，不需要第二份）；② 与 {@code kb_document} 的
 * {@code uk_kb_document_tenant_sha} 用的是同一个键，排查时两边能直接对上。
 *
 * <p><b>两段式写入（{@link #stage} + {@link #promote}）而不是一步到位</b>：摘要必须**边读边算**
 * （只读一遍流，不把整份文件读进内存），而目标文件名又依赖摘要 —— 所以先落到同目录的临时文件，
 * 算完摘要再**原子改名**。这样任何时刻都不会有「半截文件顶着原件的名字」被别的东西看见，
 * 这也正是 M5 官方验收「中断上传不留脏数据」的一半（另一半是不落 `kb_document` 行）。
 *
 * <p><b>临时文件必须与目标同目录</b>：只有同一文件系统上 {@code ATOMIC_MOVE} 才成立；
 * 跨设备会退化成「复制 + 删除」，那就又没有原子性了。
 *
 * <p>失败路径一律 {@link #discard}：中途抛异常时把临时文件删掉，**不留残渣**
 * （{@code KbUploadIntegrationTest} 的 {@code filesUnder(...)} 断言就是在钉这条）。
 */
@Component
public class KbFileStore {

    /** 临时文件前缀/后缀：用点号开头 + 非 64 位十六进制 ⇒ 绝不会与「原件」的命名混淆。 */
    private static final String TEMP_PREFIX = ".upload-";
    private static final String TEMP_SUFFIX = ".part";

    private static final int COPY_BUFFER_BYTES = 8192;

    private final Path root;

    public KbFileStore(@Value("${aihub.kb.storage.root:target/kb-storage}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    /** 存储根目录（绝对路径，已 normalize）——测试用它做定向断言。 */
    public Path root() {
        return root;
    }

    /** 某租户的目录；越界（{@code ../}）一律拒绝。 */
    public Path dirFor(long tenantId) {
        Path dir = root.resolve(Long.toString(tenantId)).normalize();
        if (!dir.startsWith(root)) {
            throw new BizException(ErrorCode.INVALID_PARAM, "非法租户路径");
        }
        return dir;
    }

    /** 原件的最终路径；越界一律拒绝（防御性，正常不可达）。 */
    public Path targetFor(long tenantId, String sha256) {
        Path dir = dirFor(tenantId);
        Path target = dir.resolve(sha256).normalize();
        if (!target.startsWith(dir)) {
            throw new BizException(ErrorCode.INVALID_PARAM, "非法存储路径");
        }
        return target;
    }

    /**
     * 边读边算 sha256 + 字节数，落成同目录的临时文件。
     *
     * <p><b>只读一遍流</b>：{@link DigestInputStream} 包住输入流，摘要与落盘共用同一次 IO。
     * 调用方**必须**在之后调用 {@link #promote}（成功）或 {@link #discard}（失败/重复），
     * 否则临时文件会留在目录里。
     */
    public StagedFile stage(long tenantId, InputStream in) {
        Path dir = dirFor(tenantId);
        Path temp = null;
        try {
            Files.createDirectories(dir);
            temp = Files.createTempFile(dir, TEMP_PREFIX, TEMP_SUFFIX);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long sizeBytes = 0;
            try (DigestInputStream digestIn = new DigestInputStream(in, digest);
                 OutputStream out = Files.newOutputStream(temp)) {
                byte[] buffer = new byte[COPY_BUFFER_BYTES];
                int read;
                while ((read = digestIn.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                    sizeBytes += read;
                }
            }
            return new StagedFile(temp, dir, HexFormat.of().formatHex(digest.digest()), sizeBytes);
        } catch (IOException e) {
            deleteQuietly(temp);
            throw new UncheckedIOException("原件落盘失败（临时文件已清理）", e);
        } catch (NoSuchAlgorithmException e) {
            deleteQuietly(temp);
            // SHA-256 是 JDK 必备算法，走到这里说明运行环境损坏 —— 不是业务错误。
            throw new IllegalStateException("JVM 缺少 SHA-256", e);
        }
    }

    /** 把临时文件**原子改名**成 {@code {root}/{tenantId}/{sha256}}；已存在则覆盖（内容必然相同）。 */
    public Path promote(StagedFile staged) {
        Path target = staged.tenantDir().resolve(staged.sha256()).normalize();
        if (!target.startsWith(staged.tenantDir())) {
            deleteQuietly(staged.temp());
            throw new BizException(ErrorCode.INVALID_PARAM, "非法存储路径");
        }
        try {
            Files.move(staged.temp(), target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return target;
        } catch (IOException e) {
            deleteQuietly(staged.temp());
            throw new UncheckedIOException("原件原子改名失败: " + target, e);
        }
    }

    /** 丢掉临时文件（重复上传、失败、或任何不需要保留的路径）。 */
    public void discard(StagedFile staged) {
        deleteQuietly(staged.temp());
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 清理失败不影响主流程：残留的临时文件以 ".upload-" 开头，不会被当成原件。
        }
    }

    /**
     * 已落盘但**尚未命名**的原件：临时文件 + 目录 + 内容摘要 + 字节数。
     *
     * @param temp      同目录的临时文件
     * @param tenantDir 该租户的目录（改名在同一目录内进行 ⇒ 同一文件系统）
     * @param sha256    内容摘要（十六进制小写，与 {@code kb_document.sha256} 同一形态）
     * @param sizeBytes 实际读到的字节数（不是 {@code Content-Length}）
     */
    public record StagedFile(Path temp, Path tenantDir, String sha256, long sizeBytes) {
    }
}
