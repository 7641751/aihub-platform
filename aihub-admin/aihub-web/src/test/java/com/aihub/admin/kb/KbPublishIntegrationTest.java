package com.aihub.admin.kb;

import com.aihub.admin.support.AbstractIntegrationTest;
import com.aihub.dao.entity.KbChunkEntity;
import com.aihub.dao.entity.KbDocumentEntity;
import com.aihub.dao.mapper.KbChunkMapper;
import com.aihub.dao.mapper.KbDocumentMapper;
import com.aihub.dao.mapper.KbOutboxMapper;
import com.aihub.mq.kb.KbParseSink;
import com.aihub.mq.kb.KbTopology;
import com.aihub.service.audit.AuditService;
import com.aihub.service.console.ConsoleClaims;
import com.aihub.service.console.ConsoleTokenService;
import com.aihub.service.kb.KbDocumentService;
import com.aihub.service.kb.KbFileStore;
import com.aihub.service.kb.KbVectorStoreClient;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.DockerClientFactory;

import java.time.Duration;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.awaitility.Awaitility.await;

/** 发件箱租约、事务回滚和两次队列交接的隔离集成测试。 */
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

    @Autowired
    private KbOutboxMapper outbox;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private KbDocumentService documents;

    @Autowired
    private KbParseSink parseSink;

    @Autowired
    private KbFileStore fileStore;

    @Autowired
    private KbVectorStoreClient vectors;

    @Autowired
    private TransactionTemplate transactions;

    @BeforeEach
    @AfterEach
    void cleanFixtures() {
        List<Long> docIds = kbDocumentMapper.selectList(new LambdaQueryWrapper<KbDocumentEntity>()
                        .eq(KbDocumentEntity::getTenantId, TENANT))
                .stream().map(KbDocumentEntity::getId).toList();
        if (!docIds.isEmpty()) {
            for (Long id : docIds) {
                jdbcTemplate.update("DELETE FROM kb_outbox WHERE doc_id = ?", id);
                vectors.deleteByDocId(id);
            }
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

        // 提交后必须发出一条可被消费的 parse 消息 ⇒ 该行会被一路推进到 READY（没有发布则永远停在 PENDING）。
        // ⚠️ 2026-10-03 控制器订正：目标状态由中间态 `EMBEDDING` 改为**终态 `READY`** —— Task 5 的 embed
        // 消费者落地后，流水线不再停在 EMBEDDING，等中间态会变成竞态（全量实测：超时红、当时状态=READY）。
        awaitStatus(id, "READY", Duration.ofSeconds(60));
        assertThat(rowsFor(TENANT)).as("上传必须只建一行（定向查）").isEqualTo(1);
    }

    /** 重传待解析文档可补建解析任务，与文档仍共享同一 ID。 */
    @Test
    void reUploadingTheSameBytesPublishesAgainSoAStuckRowCanBeRetried() throws Exception {
        byte[] content = "# retry-me\nsame bytes\n".getBytes(UTF_8);

        long first = uploadId("retry.md", content);
        awaitStatus(first, "READY", Duration.ofSeconds(60));   // 终态（中间态 EMBEDDING 已不再是停靠点，见上）

        // 模拟"卡住的行"（发布丢了 ⇒ 停在 PENDING）。再上传同一份内容必须**重新触发解析**：
        // 若不重发，状态会一直停在 PENDING ⇒ 下面的等待超时红。
        kbDocumentMapper.update(null, new LambdaUpdateWrapper<KbDocumentEntity>()
                .eq(KbDocumentEntity::getId, first).set(KbDocumentEntity::getStatus, "PENDING"));

        long second = uploadId("retry-again.md", content);
        assertThat(second).as("同内容必须命中同一行（幂等，D5）").isEqualTo(first);
        // 从 PENDING 一路回到 READY ⇒ 证明"重复上传确实又发了一条 parse"（不重发就永远卡在 PENDING）。
        awaitStatus(first, "READY", Duration.ofSeconds(60));
        assertThat(rowsFor(TENANT)).as("重复上传仍不许新增行").isEqualTo(1);
    }

    @Test
    void expiredLeaseIsRecoveredAndAnUnroutableMessageIsRetriedAfterBindingReturns() {
        long docId = Long.MAX_VALUE;
        String routingKey = "kb.outbox.integration." + UUID.randomUUID();
        String payload = "parse:" + docId;
        Queue destination = new Queue(routingKey, false);
        jdbcTemplate.update("""
                INSERT INTO kb_outbox (doc_id, generation, routing_key, payload, available_at, lease_token)
                VALUES (?, 4, ?, ?, TIMESTAMPADD(SECOND, 30, UTC_TIMESTAMP(3)), 'abandoned')
                """, docId, routingKey, payload);
        try {
            Long id = jdbcTemplate.queryForObject("SELECT id FROM kb_outbox WHERE doc_id = ?", Long.class, docId);
            assertThat(outbox.claim(id, "competing")).isZero();
            assertThat(outbox.complete(id, "competing")).isZero();
            jdbcTemplate.update("UPDATE kb_outbox SET available_at = UTC_TIMESTAMP(3) WHERE id = ?", id);

            await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                    assertThat(jdbcTemplate.queryForObject("""
                                    SELECT COUNT(*) FROM kb_outbox WHERE id = ? AND failures >= 1 AND lease_token IS NULL
                                    """, Integer.class, id)).isEqualTo(1));
            assertThat(outbox.complete(id, "abandoned")).isZero();

            amqpAdmin.declareQueue(destination);
            amqpAdmin.declareBinding(BindingBuilder.bind(destination)
                    .to(new TopicExchange(KbTopology.EXCHANGE)).with(routingKey));
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                    assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM kb_outbox WHERE id = ?",
                            Integer.class, id)).isZero());

            Message delivered = rabbitTemplate.receive(destination.getName(), 5_000);
            assertThat(delivered).isNotNull();
            assertThat(delivered.getBody()).asString(UTF_8).isEqualTo(payload);
            assertThat(delivered.getMessageProperties().getHeaders()).containsEntry("x-aihub-kb-generation", 4L);
        } finally {
            jdbcTemplate.update("DELETE FROM kb_outbox WHERE doc_id = ?", docId);
            amqpAdmin.deleteQueue(destination.getName());
        }
    }

    @Test
    void onlyTheCurrentLeaseOwnerCanCompleteOrRetryATask() {
        transactions.executeWithoutResult(transaction -> {
            long docId = Long.MAX_VALUE - 1;
            outbox.enqueue(docId, 0, KbTopology.PARSE_ROUTING_KEY, "parse:" + docId);
            Long id = jdbcTemplate.queryForObject("SELECT id FROM kb_outbox WHERE doc_id = ?", Long.class, docId);
            assertThat(outbox.claim(id, "first")).isEqualTo(1);
            assertThat(outbox.claim(id, "second")).isZero();
            jdbcTemplate.update("UPDATE kb_outbox SET available_at = UTC_TIMESTAMP(3) WHERE id = ?", id);
            assertThat(outbox.claim(id, "second")).isEqualTo(1);
            assertThat(outbox.complete(id, "first")).isZero();
            assertThat(outbox.retry(id, "first")).isZero();
            assertThat(outbox.complete(id, "second")).isEqualTo(1);
            transaction.setRollbackOnly();
        });
    }

    @Test
    void uploadRollbackRemovesDocumentAuditAndParseTask() {
        var docId = new AtomicLong();
        transactions.executeWithoutResult(transaction -> {
            var upload = documents.upload(TENANT, "rollback.md",
                    new ByteArrayInputStream(("upload rollback " + UUID.randomUUID()).getBytes(UTF_8)),
                    new AuditService.Actor("USER", "1"));
            docId.set(upload.row().getId());
            assertThat(upload.row().getIngestGeneration()).isEqualTo(1);
            assertThat(pendingTasks(docId.get(), KbTopology.PARSE_ROUTING_KEY)).isEqualTo(1);
            assertThat(uploadAudits(docId.get())).isEqualTo(1);
            transaction.setRollbackOnly();
        });

        assertThat(kbDocumentMapper.selectById(docId.get())).isNull();
        assertThat(uploadAudits(docId.get())).isZero();
        assertThat(pendingTasks(docId.get(), KbTopology.PARSE_ROUTING_KEY)).isZero();
    }

    @Test
    void parseRollbackRemovesChunksAndEmbedTasks() throws Exception {
        var staged = fileStore.stage(TENANT, new ByteArrayInputStream(largeDocument()));
        fileStore.promote(staged);
        var document = new KbDocumentEntity();
        document.setTenantId(TENANT);
        document.setFilename("parse-rollback.md");
        document.setSizeBytes(staged.sizeBytes());
        document.setSha256(staged.sha256());
        document.setStatus("PENDING");
        document.setChunkCount(0);
        kbDocumentMapper.insert(document);
        long id = document.getId();

        transactions.executeWithoutResult(transaction -> {
            parseSink.parse(id, 0);
            assertThat(kbDocumentMapper.selectById(id).getStatus()).isEqualTo("EMBEDDING");
            assertThat(countChunks(id, false)).isEqualTo(25);
            assertThat(pendingTasks(id, KbTopology.EMBED_ROUTING_KEY)).isEqualTo(3);
            transaction.setRollbackOnly();
        });

        assertThat(kbDocumentMapper.selectById(id).getStatus()).isEqualTo("PENDING");
        assertThat(countChunks(id, false)).isZero();
        assertThat(pendingTasks(id, KbTopology.EMBED_ROUTING_KEY)).isZero();
    }

    @Test
    void uploadDuringBrokerPauseRecoversToTwentyFiveVectors() throws Exception {
        var docker = DockerClientFactory.instance().client();
        String broker = RABBITMQ.getContainerId();
        long id;
        docker.pauseContainerCmd(broker).exec();
        try {
            id = uploadId("paused-broker.md", largeDocument());
            assertThat(kbDocumentMapper.selectById(id).getStatus()).isEqualTo("PENDING");
            assertThat(pendingTasks(id, KbTopology.PARSE_ROUTING_KEY)).isEqualTo(1);
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                    assertThat(jdbcTemplate.queryForObject(
                            "SELECT failures FROM kb_outbox WHERE doc_id = ? AND routing_key = ?",
                            Integer.class, id, KbTopology.PARSE_ROUTING_KEY)).isPositive());
        } finally {
            docker.unpauseContainerCmd(broker).exec();
        }

        assertReadyAndDrained(id);
    }

    @Test
    void returningTheEmbedBindingRecoversTheDocumentFromTheOutbox() throws Exception {
        var binding = BindingBuilder.bind(new Queue(KbTopology.EMBED_QUEUE))
                .to(new TopicExchange(KbTopology.EXCHANGE)).with(KbTopology.EMBED_ROUTING_KEY);
        long id;
        amqpAdmin.removeBinding(binding);
        try {
            id = uploadId("unbound-embed.md", largeDocument());
            awaitStatus(id, "EMBEDDING", Duration.ofSeconds(30));
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                assertThat(jdbcTemplate.queryForObject("""
                        SELECT COUNT(*) FROM kb_outbox WHERE doc_id = ? AND routing_key = ? AND failures > 0
                        """, Long.class, id, KbTopology.EMBED_ROUTING_KEY)).isPositive();
            });
            assertThat(pendingTasks(id, KbTopology.EMBED_ROUTING_KEY)).isEqualTo(3);
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT TIMESTAMPDIFF(MICROSECOND, MIN(created_at), UTC_TIMESTAMP(3))
                    FROM kb_outbox WHERE doc_id = ?
                    """, Long.class, id)).isPositive();
        } finally {
            amqpAdmin.declareBinding(binding);
        }

        assertReadyAndDrained(id);
    }

    private void assertReadyAndDrained(long id) throws InterruptedException {
        awaitStatus(id, "READY", Duration.ofSeconds(90));
        assertThat(countChunks(id, true)).isEqualTo(25);
        assertThat(vectors.countByDocId(id)).isEqualTo(25);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM kb_outbox WHERE doc_id = ?",
                        Long.class, id)).isZero());
    }

    private long countChunks(long id, boolean embeddedOnly) {
        return kbChunkMapper.selectCount(new LambdaQueryWrapper<KbChunkEntity>()
                .eq(KbChunkEntity::getDocId, id).isNotNull(embeddedOnly, KbChunkEntity::getEmbeddedAt));
    }

    private long pendingTasks(long id, String routingKey) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM kb_outbox WHERE doc_id = ? AND routing_key = ?",
                Long.class, id, routingKey);
    }

    private long uploadAudits(long id) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM audit_log WHERE action = 'KB_DOCUMENT_UPLOAD'
                AND target_type = 'KB_DOCUMENT' AND target_id = ?
                """, Long.class, String.valueOf(id));
    }

    private static byte[] largeDocument() {
        return (UUID.randomUUID() + "x".repeat(17_000 - 36)).getBytes(UTF_8);
    }

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
