package com.aihub.admin.kb;

import com.aihub.admin.support.AbstractIntegrationTest;
import com.aihub.dao.entity.AuditLogEntity;
import com.aihub.dao.entity.KbDocumentEntity;
import com.aihub.dao.mapper.AuditLogMapper;
import com.aihub.dao.mapper.KbDocumentMapper;
import com.aihub.service.console.ConsoleClaims;
import com.aihub.service.console.ConsoleTokenService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * M5 Task 2 的**线上契约**：上传（同步半段）—— 原子落盘 + sha256 去重 + 审计 + 列表边界。
 *
 * <p><b>真容器 + 真 HTTP + 真令牌</b>，复用**已有**的 Spring 上下文（继承 {@link AbstractIntegrationTest}）。
 * 刻意**不**为存储根目录声明 {@code @TestPropertySource} / {@code @DynamicPropertySource}：改属性会改变
 * 上下文缓存键 ⇒ fork 出第 8 个上下文，违反 M5 的「{@code Tomcat started on port} 保持 7」硬约束。
 * 存储根目录因此用 {@code application.yml} 的**默认值**（{@code target/kb-storage}，可丢弃），
 * 每个用例在 {@code @BeforeEach} 里**按租户**清自己的子目录。
 *
 * <p><b>「无残留文件」断言必须定向</b>：只查 {@code {root}/{tenantId}/} 子目录，**不是**整个根目录 ——
 * 容器与根目录都是 JVM 级共享的，全根断言会变成顺序依赖。
 *
 * <p><b>本项目没有 multipart 测试先例</b>：请求体自搭 {@link MultiValueMap} + {@link ByteArrayResource}
 * （覆写 {@link NamedByteArrayResource#getFilename()}），{@code tenantId} 作为**文本 part** 一起发。
 * {@code ConsoleAuthFilter} 不读 body，因此不会吃掉 multipart 流。
 *
 * <p>审计断言按**本次文档 id**（自增主键，不会跨用例复用）+ {@code action} 定向查，绝不做全表计数
 * （{@code audit_log} 是共享表）。
 *
 * <p><b>唯一一处 {@code @TestPropertySource} 是控制台签名密钥</b>（D16：{@code application.yml} 对
 * {@code aihub.console.secret} **刻意没有默认值**，
 * 空 = 门关着 ⇒ 集成测试必须自己注入一把合规的）：下面这把字面量与 {@code ConsoleLoginIntegrationTest} /
 * {@code ApiKeyAdminIntegrationTest} **逐字相同**，且本类**不加** {@code @Import} —— 两件事合起来才让本类
 * **共用它们那个已有的 Spring 上下文**（上下文缓存键由合并后的配置决定）。
 * 这是本项目的硬约束：套件里 {@code Tomcat started on port} 的次数必须保持 **7**；换个密钥字面量、
 * 或加一个 {@code @Import}（哪怕只导入一个测试专用配置类），都会 fork 出**第 8 个**上下文 ——
 * {@code ChannelAdminIntegrationTest:589} 已经用一次实测记下过这个坑。
 */
@TestPropertySource(properties = {
        "aihub.console.secret=console-it-secret-0123456789abcdefghijklmn"
})
class KbUploadIntegrationTest extends AbstractIntegrationTest {

    /**
     * 本用例专用租户：种子值刻意远离其他夹具（{@code 91xxxx} 已被别的套件占用习惯），
     * 每个用例在 {@code @BeforeEach} 里把它在 {@code kb_document} 的子集清空。
     */
    private static final long TENANT = 920_001L;

    private static final String KB_TARGET_TYPE = "KB_DOCUMENT";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private KbDocumentMapper kbDocumentMapper;

    @Autowired
    private AuditLogMapper auditLogMapper;

    @Autowired
    private ConsoleTokenService consoleTokenService;

    @LocalServerPort
    private int port;

    /** 与生产 {@code application.yml} 同一个键；测试**只读**它，不改它。 */
    @Value("${aihub.kb.storage.root:target/kb-storage}")
    private String storageRoot;

    @BeforeEach
    @AfterEach
    void cleanFixtures() throws IOException {
        kbDocumentMapper.delete(new LambdaQueryWrapper<KbDocumentEntity>()
                .eq(KbDocumentEntity::getTenantId, TENANT));
        deleteRecursively(root().resolve(Long.toString(TENANT)));
    }

    // ---------------------------------------------------------------- 1) 落盘 + 建行

    @Test
    void uploadingATextFileLandsOnDiskAndCreatesAPendingRow() throws Exception {
        byte[] content = "# hello\nworld\n".getBytes(UTF_8);

        ResponseEntity<String> res = postMultipart("/api/kb/documents", TENANT, "notes.md", content);

        assertThat(res.getStatusCode()).as("POST /api/kb/documents 必须 200（响应体=%s）", res.getBody())
                .isEqualTo(HttpStatus.OK);
        JsonNode data = body(res).path("data");
        long id = data.path("id").asLong();
        assertThat(id).as("data.id 必须存在（响应体=%s）", res.getBody()).isPositive();
        assertThat(data.path("status").asText()).as("新上传必须是 PENDING").isEqualTo("PENDING");
        assertThat(data.path("chunkCount").asInt()).as("新行 chunk_count 必须是 0").isZero();
        assertThat(data.path("duplicate").asBoolean()).as("首次上传不是重复").isFalse();

        assertThat(rowsFor(TENANT)).as("定向查（按本租户）").isEqualTo(1);
        assertThat(Files.readString(root().resolve(TENANT + "/" + sha256Of(content))))
                .as("原件必须逐字落盘（按 {root}/{tenant}/{sha256} 命名）")
                .isEqualTo("# hello\nworld\n");
        assertThat(filesUnder(root().resolve(Long.toString(TENANT))))
                .as("不许留临时文件（.upload-*.part）").hasSize(1);
        assertThat(auditRowsFor(id, "KB_DOCUMENT_UPLOAD")).as("成功上传必须留恰好一条审计行").isEqualTo(1);
    }

    // ---------------------------------------------------------------- 2) 幂等（同内容二次上传）

    @Test
    void uploadingTheSameBytesTwiceIsIdempotentAndLeavesNoStrayFile() throws Exception {
        byte[] content = "idempotent-content-line\n".getBytes(UTF_8);

        long first = uploadId(TENANT, "a.md", content);
        ResponseEntity<String> secondRes = postMultipart("/api/kb/documents", TENANT, "b.md", content);

        assertThat(secondRes.getStatusCode()).as("重复上传必须 200，不是 409/500（响应体=%s）", secondRes.getBody())
                .isEqualTo(HttpStatus.OK);
        long second = body(secondRes).path("data").path("id").asLong();
        assertThat(body(secondRes).path("data").path("duplicate").asBoolean()).as("第二次必须标记为重复").isTrue();
        assertThat(second).as("同租户同内容必须返回同一行（幂等）").isEqualTo(first);

        assertThat(rowsFor(TENANT)).as("重复上传不许新增行").isEqualTo(1);
        assertThat(filesUnder(root().resolve(Long.toString(TENANT))))
                .as("第二次写的临时文件必须被删掉，只剩原件").hasSize(1);
    }

    // ---------------------------------------------------------------- 3) 中断上传不留脏数据

    @Test
    void aTruncatedUploadLeavesNoRowAndNoFile() throws Exception {
        // 故意只发一半就断（Content-Length 说 N、实际发更少），请求体里 multipart 没有结尾边界。
        int status = postTruncatedMultipart("/api/kb/documents", TENANT, "half.md", 4096, 96);

        assertThat(status).as("截断上传必须被拒绝（4xx）").isBetween(400, 499);
        assertThat(rowsFor(TENANT)).as("M5 官方验收：中断上传不留脏数据（行）").isZero();
        assertThat(filesUnder(root().resolve(Long.toString(TENANT))))
                .as("中断上传不留文件（含临时文件）").isEmpty();
    }

    // ---------------------------------------------------------------- 4) 入参拒绝

    @Test
    void uploadRejectsMissingTenantIdAndUnknownExtension() throws Exception {
        // 缺 tenantId ⇒ 400 INVALID_PARAM（写操作的租户取请求体，CONVENTIONS §10 R2）
        ResponseEntity<String> noTenant = postMultipart("/api/kb/documents", null, "a.md", "# x\n".getBytes(UTF_8));
        assertThat(noTenant.getStatusCode()).as("缺 tenantId 必须 400（响应体=%s）", noTenant.getBody())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(body(noTenant).path("code").asText()).isEqualTo("INVALID_PARAM");

        // 未知扩展名 ⇒ 400。⚠️ 2026-10-05 控制器订正：这里原来用 `.pdf` 当反例，但 **M5 Task 7 已把 pdf 加入白名单**
        //（白名单 = md/txt/pdf），继续用 pdf 会让本用例把"合法"判成"非法"。换成一个仍然不支持的扩展名。
        ResponseEntity<String> badExt = postMultipart("/api/kb/documents", TENANT, "a.exe", "# x\n".getBytes(UTF_8));
        assertThat(badExt.getStatusCode()).as("未知扩展名必须 400（响应体=%s）", badExt.getBody())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(body(badExt).path("code").asText()).isEqualTo("INVALID_PARAM");

        assertThat(rowsFor(TENANT)).as("两种拒绝都不许落行").isZero();
    }

    // ---------------------------------------------------------------- 5) 列表租户缺省 + 分页边界

    @Test
    void listingDocumentsDefaultsToTokenTenantAndClampsPaginationBounds() throws Exception {
        uploadId(TENANT, "g1.md", "# one\n".getBytes(UTF_8));
        uploadId(TENANT, "g2.md", "# two\n".getBytes(UTF_8));

        // 资源列表：缺省 tenantId = 令牌里的租户（CONVENTIONS §10 R3.2）
        ResponseEntity<String> def = get("/api/kb/documents");
        assertThat(def.getStatusCode()).as("GET 列表必须 200（响应体=%s）", def.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode defData = body(def).path("data");
        assertThat(defData.path("page").asInt()).as("page 缺省 0").isZero();
        assertThat(defData.path("size").asInt()).as("size 缺省 20").isEqualTo(20);
        assertThat(defData.path("total").asLong()).as("缺省租户下必须能看到本租户的文档").isGreaterThanOrEqualTo(2);

        // size > 200 钳到 200
        ResponseEntity<String> clamped = get("/api/kb/documents?size=1000");
        assertThat(clamped.getStatusCode()).as("GET 列表必须 200（响应体=%s）", clamped.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(body(clamped).path("data").path("size").asInt()).as("size 上界必须钳到 200").isEqualTo(200);

        // size < 1 ⇒ 400
        ResponseEntity<String> badSize = get("/api/kb/documents?size=0");
        assertThat(badSize.getStatusCode()).as("size<1 必须 400（响应体=%s）", badSize.getBody())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(body(badSize).path("code").asText()).isEqualTo("INVALID_PARAM");

        // page < 0 ⇒ 400
        ResponseEntity<String> badPage = get("/api/kb/documents?page=-1");
        assertThat(badPage.getStatusCode()).as("page<0 必须 400（响应体=%s）", badPage.getBody())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(body(badPage).path("code").asText()).isEqualTo("INVALID_PARAM");
    }

    // ---------------------------------------------------------------- HTTP 助手

    private ResponseEntity<String> postMultipart(String path, Long tenantId, String filename, byte[] content) {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        if (tenantId != null) {
            parts.add("tenantId", String.valueOf(tenantId));
        }
        parts.add("file", new NamedByteArrayResource(content, filename));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(token());
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(parts, headers), String.class);
    }

    private ResponseEntity<String> get(String path) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token());
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private long uploadId(long tenantId, String filename, byte[] content) throws Exception {
        ResponseEntity<String> res = postMultipart("/api/kb/documents", tenantId, filename, content);
        assertThat(res.getStatusCode()).as("上传必须 200（响应体=%s）", res.getBody()).isEqualTo(HttpStatus.OK);
        return body(res).path("data").path("id").asLong();
    }

    /**
     * 原始 socket 发一条**被截断**的 multipart 请求：{@code Content-Length} 声明的长度**大于**实际发送的
     * 字节数，写完后**半关闭**（{@code shutdownOutput}）让服务器读到 EOF、但还能把 4xx 写回来。
     * 返回解析出的 HTTP 状态码。
     */
    private int postTruncatedMultipart(String path, long tenantId, String filename, int declaredLength, int actualBytes)
            throws IOException {
        String boundary = "----truncated" + UUID.randomUUID().toString().replace("-", "");
        String preamble = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"tenantId\"\r\n\r\n"
                + tenantId + "\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: text/markdown\r\n\r\n"
                // 故意**不写**结尾边界（"--boundary--"），使 multipart 体不完整
                + "0123456789abcdefghijklmnopqrstuvwxyz";
        byte[] body = preamble.getBytes(UTF_8);
        int sendLength = Math.min(actualBytes, body.length);

        String head = "POST " + path + " HTTP/1.1\r\n"
                + "Host: 127.0.0.1:" + port + "\r\n"
                + "Authorization: Bearer " + token() + "\r\n"
                + "Content-Type: multipart/form-data; boundary=" + boundary + "\r\n"
                + "Content-Length: " + declaredLength + "\r\n"
                + "Connection: close\r\n\r\n";

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            out.write(head.getBytes(StandardCharsets.US_ASCII));
            out.write(body, 0, sendLength);
            out.flush();
            socket.shutdownOutput();
            byte[] response = socket.getInputStream().readAllBytes();
            return statusOf(response);
        }
    }

    private static int statusOf(byte[] response) {
        String text = new String(response, StandardCharsets.US_ASCII);
        int idx = text.indexOf("HTTP/1.1 ");
        if (idx < 0) {
            throw new IllegalStateException("截断请求没有收到 HTTP 响应（原始响应字节数=" + response.length + "）");
        }
        return Integer.parseInt(text.substring(idx + "HTTP/1.1 ".length(), idx + "HTTP/1.1 ".length() + 3).trim());
    }

    // ---------------------------------------------------------------- 定向断言助手

    private Path root() {
        return Path.of(storageRoot).toAbsolutePath().normalize();
    }

    private long rowsFor(long tenantId) {
        return kbDocumentMapper.selectCount(new LambdaQueryWrapper<KbDocumentEntity>()
                .eq(KbDocumentEntity::getTenantId, tenantId));
    }

    private long auditRowsFor(long docId, String action) {
        return auditLogMapper.selectCount(new LambdaQueryWrapper<AuditLogEntity>()
                .eq(AuditLogEntity::getAction, action)
                .eq(AuditLogEntity::getTargetType, KB_TARGET_TYPE)
                .eq(AuditLogEntity::getTargetId, String.valueOf(docId)));
    }

    private static List<Path> filesUnder(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.filter(Files::isRegularFile).sorted().toList();
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static JsonNode body(ResponseEntity<String> res) throws Exception {
        return MAPPER.readTree(res.getBody());
    }

    private static String sha256Of(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private String token() {
        long now = Instant.now().getEpochSecond();
        return consoleTokenService.issue(new ConsoleClaims(1L, TENANT, ConsoleClaims.ROLE_ADMIN, now, now + 3_600L));
    }

    /** 让 {@code ByteArrayResource.getFilename()} 返回真实文件名（multipart 的 {@code file} part 要带名字）。 */
    private static final class NamedByteArrayResource extends ByteArrayResource {

        private final String filename;

        NamedByteArrayResource(byte[] bytes, String filename) {
            super(bytes);
            this.filename = filename;
        }

        @Override
        public String getFilename() {
            return filename;
        }
    }
}
