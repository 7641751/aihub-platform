package com.aihub.admin.kb;

import com.aihub.admin.support.AbstractIntegrationTest;
import com.aihub.dao.entity.KbChunkEntity;
import com.aihub.dao.entity.KbDocumentEntity;
import com.aihub.dao.mapper.KbChunkMapper;
import com.aihub.dao.mapper.KbDocumentMapper;
import com.aihub.service.console.ConsoleClaims;
import com.aihub.service.console.ConsoleTokenService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
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

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * M5 Task 3 的**线上契约**：上传事务**提交后**，一条 {@code parse:{docId}} 真的被投出去并被解析。
 *
 * <p><b>真容器 + 真 HTTP + 真令牌 + 真 broker</b>，复用**已有**的 Spring 上下文（继承
 * {@link AbstractIntegrationTest}）。{@code @TestPropertySource} 那把字面量与 Task 2 的
 * {@code KbUploadIntegrationTest} / {@code ConsoleLoginIntegrationTest} **逐字相同**、且本类
 * **不加** {@code @Import} ⇒ 共用同一个上下文（判据：全量套件 {@code Tomcat started on port} 仍是 7）。
 *
 * <p><b>⚠️ 判据自 Task 4 起改为数据库状态（计划 Task 3 javadoc 已登记）</b>：Task 4 的
 * {@code KbParseConsumer} 会在**每个** Spring 上下文里订阅 {@code kb.parse}，于是"从队列收消息"的断言
 * 会被消费者抢走（实测：这两条原断言在 Task 4 后稳定红 —— {@code Expecting value to be true but was false}
 * 于 {@code awaitParseMessageFor}）。⇒ 改成等**数据库状态**：{@code kb_document.status} 走到
 * {@code EMBEDDING} 就等于"消息已发出**且**被消费"，比"盯着队列"既稳又不与消费者抢。
 */
@TestPropertySource(properties = {
        "aihub.console.secret=console-it-secret-0123456789abcdefghijklmn"
})
class KbPublishIntegrationTest extends AbstractIntegrationTest {

    /** 本用例专用租户（与 Task 2 的 920001 区分，避免共享表上的夹具互相干扰）。 */
    private static final long TENANT = 920_002L;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private KbDocumentMapper kbDocumentMapper;

    @Autowired
    private KbChunkMapper kbChunkMapper;

    @Autowired
    private ConsoleTokenService consoleTokenService;

    @BeforeEach
    @AfterEach
    void cleanFixtures() {
        List<Long> docIds = kbDocumentMapper.selectList(new LambdaQueryWrapper<KbDocumentEntity>()
                        .eq(KbDocumentEntity::getTenantId, TENANT))
                .stream().map(KbDocumentEntity::getId).toList();
        if (!docIds.isEmpty()) {
            kbChunkMapper.delete(new LambdaQueryWrapper<KbChunkEntity>().in(KbChunkEntity::getDocId, docIds));
        }
        kbDocumentMapper.delete(new LambdaQueryWrapper<KbDocumentEntity>()
                .eq(KbDocumentEntity::getTenantId, TENANT));
    }

    @Test
    void uploadingADocumentPublishesAParseMessageAfterCommit() throws Exception {
        byte[] content = "# publish-parse\nhello\n".getBytes(UTF_8);

        ResponseEntity<String> res = postMultipart("/api/kb/documents", TENANT, "publish.md", content);
        assertThat(res.getStatusCode()).as("上传必须 200（响应体=%s）", res.getBody()).isEqualTo(HttpStatus.OK);
        long id = body(res).path("data").path("id").asLong();
        assertThat(id).as("data.id 必须存在（响应体=%s）", res.getBody()).isPositive();

        // 提交后必须发出一条可被消费的 parse 消息 ⇒ 该行会被推进到 EMBEDDING（没有发布则永远停在 PENDING）。
        awaitStatus(id, "EMBEDDING", Duration.ofSeconds(20));
        assertThat(rowsFor(TENANT)).as("上传必须只建一行（定向查）").isEqualTo(1);
    }

    /**
     * 控制器裁决（2026-10-03）：**重复上传也会重新触发解析** —— 这是"发布丢了 ⇒ 行停在 PENDING"的
     * **唯一现成补救手段**（DLQ 只管**消费端**失败、计数器只**观测**，两者都不会把消息重新推下去）。
     *
     * <p>代价如实登记：对一份**已经 READY** 的文档再传一次，会多做一次解析（有界，且 Task 4/5 的写入按设计幂等）。
     */
    @Test
    void reUploadingTheSameBytesPublishesAgainSoAStuckRowCanBeRetried() throws Exception {
        byte[] content = "# retry-me\nsame bytes\n".getBytes(UTF_8);

        long first = uploadId("retry.md", content);
        awaitStatus(first, "EMBEDDING", Duration.ofSeconds(20));

        // 模拟"卡住的行"（发布丢了 ⇒ 停在 PENDING）。再上传同一份内容必须**重新触发解析**：
        // 若不重发，状态会一直停在 PENDING ⇒ 下面的等待超时红。
        kbDocumentMapper.update(null, new LambdaUpdateWrapper<KbDocumentEntity>()
                .eq(KbDocumentEntity::getId, first).set(KbDocumentEntity::getStatus, "PENDING"));

        long second = uploadId("retry-again.md", content);
        assertThat(second).as("同内容必须命中同一行（幂等，D5）").isEqualTo(first);
        awaitStatus(first, "EMBEDDING", Duration.ofSeconds(20));
        assertThat(rowsFor(TENANT)).as("重复上传仍不许新增行").isEqualTo(1);
    }

    // ---------------------------------------------------------------- 助手

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

    /** 上传一份内容并返回 {@code data.id}（两处断言共用，避免把响应解析抄两遍）。 */
    private long uploadId(String filename, byte[] content) throws Exception {
        ResponseEntity<String> res = postMultipart("/api/kb/documents", TENANT, filename, content);
        assertThat(res.getStatusCode()).as("上传必须 200（响应体=%s）", res.getBody()).isEqualTo(HttpStatus.OK);
        return body(res).path("data").path("id").asLong();
    }

    /** 有界轮询数据库状态（不抢队列、不许 sleep 硬等）；超时把当时的 status 打进失败消息。 */
    private void awaitStatus(long docId, String expected, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            KbDocumentEntity row = kbDocumentMapper.selectById(docId);
            if (row != null && expected.equals(row.getStatus())) {
                return;
            }
            Thread.sleep(100);
        }
        KbDocumentEntity row = kbDocumentMapper.selectById(docId);
        fail("等待 doc " + docId + " 到达 " + expected + " 超时；当时状态=" + (row == null ? "<无此行>" : row.getStatus()));
    }

    private long rowsFor(long tenantId) {
        return kbDocumentMapper.selectCount(new LambdaQueryWrapper<KbDocumentEntity>()
                .eq(KbDocumentEntity::getTenantId, tenantId));
    }

    private static JsonNode body(ResponseEntity<String> res) throws Exception {
        return MAPPER.readTree(res.getBody());
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
