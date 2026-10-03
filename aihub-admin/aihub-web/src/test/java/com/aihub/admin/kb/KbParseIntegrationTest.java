package com.aihub.admin.kb;

import com.aihub.admin.support.AbstractIntegrationTest;
import com.aihub.dao.entity.KbChunkEntity;
import com.aihub.dao.entity.KbDocumentEntity;
import com.aihub.dao.mapper.KbChunkMapper;
import com.aihub.dao.mapper.KbDocumentMapper;
import com.aihub.mq.kb.KbEmbedBatch;
import com.aihub.mq.kb.KbMessageCodec;
import com.aihub.mq.kb.KbTopology;
import com.aihub.service.console.ConsoleClaims;
import com.aihub.service.console.ConsoleTokenService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * M5 Task 4 的**线上契约**：一条 {@code parse:{docId}} 被解析成 {@code kb_chunk} 行、状态走到
 * {@code EMBEDDING}、并按 {@code ceil(N/batchSize)} 分发 {@code kb.embed}。
 *
 * <p><b>真容器 + 真 HTTP + 真令牌 + 真 broker + 真消费者</b>，复用**已有**的 Spring 上下文
 * （继承 {@link AbstractIntegrationTest}）。{@code @TestPropertySource} 那把字面量与
 * {@code KbUploadIntegrationTest} / {@code KbPublishIntegrationTest} / {@code ConsoleLoginIntegrationTest}
 * **逐字相同**、且本类**不加** {@code @Import} ⇒ 共用同一个上下文（判据：全量套件
 * {@code Tomcat started on port} 仍是 7）。
 *
 * <p><b>断言一律走数据库状态</b>（{@code kb_document.status} / {@code kb_chunk} 行数），**不抢 {@code kb.parse} 队列**：
 * Task 4 的消费者一落地就会在每个 Spring 上下文里订阅 {@code kb.parse}，抢队列会退化成 flaky。
 * 唯一从队列取消息的是 {@code kb.embed}（本任务还没有 embed 消费者 ⇒ 消息留在队列里）。
 * ⚠️ <b>那条 embed 断言有保质期</b>：Task 5 的消费者一落地就会抢走 {@code kb.embed}，届时必须改成
 * 数据库判据（{@code kb_chunk.embedded_at}）—— 这是**已知的短期判据**，不是遗漏。
 *
 * <p><b>超时必须有界且带诊断</b>：{@link #awaitUntil} 不用 {@code sleep} 硬等，超时把当时的
 * {@code status}/{@code chunk} 数打进失败消息，免得排查只剩"超时了"三个字。
 */
@TestPropertySource(properties = {
        "aihub.console.secret=console-it-secret-0123456789abcdefghijklmn"
})
class KbParseIntegrationTest extends AbstractIntegrationTest {

    /** 本用例专用租户（与 Task 2 的 920001 / Task 3 的 920002 区分，避免共享表上的夹具互相干扰）。 */
    private static final long TENANT = 920_003L;

    /** 每批段数 = {@code aihub.kb.embed.batch-size} 的**默认值**（测试不许覆盖 ⇒ 会 fork 上下文）。 */
    private static final int BATCH_SIZE = 10;

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
    private RabbitTemplate rabbitTemplate;

    @BeforeEach
    @AfterEach
    void cleanFixtures() {
        // 先按本租户的 doc 删 kb_chunk（没有外键，得自己来），再删 kb_document。
        List<Long> docIds = kbDocumentMapper.selectList(new LambdaQueryWrapper<KbDocumentEntity>()
                        .eq(KbDocumentEntity::getTenantId, TENANT))
                .stream().map(KbDocumentEntity::getId).toList();
        if (!docIds.isEmpty()) {
            kbChunkMapper.delete(new LambdaQueryWrapper<KbChunkEntity>()
                    .in(KbChunkEntity::getDocId, docIds));
        }
        kbDocumentMapper.delete(new LambdaQueryWrapper<KbDocumentEntity>()
                .eq(KbDocumentEntity::getTenantId, TENANT));
    }

    // ---------------------------------------------------------------- 1) 解析 → 落段 → 分发

    @Test
    void aParseMessageTurnsAFileIntoTrackedChunksAndEmitsEmbedBatches() throws Exception {
        long id = uploadId(TENANT, "doc.md", mdWith(25, "段落"));
        publishParse(id);

        awaitUntil(Duration.ofSeconds(20), () -> "EMBEDDING".equals(statusOf(id)), id);

        assertThat(chunkRowsFor(id)).as("定向：只数这个 doc 的段").isEqualTo(25);
        assertThat(embeddedCountFor(id)).as("解析阶段还没嵌入").isZero();

        List<KbEmbedBatch> batches = awaitEmbedBatches(id, 3, Duration.ofSeconds(15));
        assertThat(batches)
                .as("25 段 / 每批 10 ⇒ 3 条消息（本条判据有保质期：Task 5 的消费者会抢走 kb.embed）")
                .hasSize(3);
        assertThat(batches).extracting(KbEmbedBatch::seqFrom).containsExactlyInAnyOrder(0, 10, 20);
        assertThat(batches).extracting(KbEmbedBatch::seqTo).containsExactlyInAnyOrder(9, 19, 24);
    }

    // ---------------------------------------------------------------- 2) 重放不产生重复段

    @Test
    void aReplayedParseMessageDoesNotDuplicateChunks() throws Exception {
        long id = uploadId(TENANT, "replay.md", mdWith(25, "重放"));
        publishParse(id);
        awaitUntil(Duration.ofSeconds(20), () -> "EMBEDDING".equals(statusOf(id)), id);
        assertThat(chunkRowsFor(id)).isEqualTo(25);

        // 把状态退回 PENDING，**强制**重放真正走到写段那一步：否则状态守卫（EMBEDDING ∉ {PENDING,PARSING}）
        // 会在写段之前就 ack 丢弃，于是这条用例根本覆盖不到 upsert（"重放不重复"就退化成空断言）。
        // RED 证据正是指向这一步：把 upsert 改成裸 insert ⇒ 撞唯一键 ⇒ 回滚 ⇒ 状态停 PENDING ⇒ 超时红；
        // 把 uk_kb_chunk_doc_seq 换成普通索引 ⇒ 写入 50 段 ⇒ 计数断言红。
        kbDocumentMapper.update(null, new LambdaUpdateWrapper<KbDocumentEntity>()
                .eq(KbDocumentEntity::getId, id).set(KbDocumentEntity::getStatus, "PENDING"));
        assertThat(statusOf(id)).as("重置后必须是 PENDING（否则下面的重放覆盖不到写段路径）").isEqualTo("PENDING");

        publishParse(id);
        awaitUntil(Duration.ofSeconds(20), () -> "EMBEDDING".equals(statusOf(id)), id);

        assertThat(chunkRowsFor(id)).as("同一条消息重放 ⇒ 段数不变（upsert 幂等）").isEqualTo(25);
    }

    // ---------------------------------------------------------------- 3) READY/FAILED 的消息被 ack 丢弃

    @Test
    void aMessageForAReadyDocumentIsAckedAndIgnored() throws Exception {
        // 用**真实上传**（原件落盘），先把行推到 EMBEDDING（证明它确实可被解析），再手工置 READY。
        // 关键是**文件存在**：若状态守卫（裁定 #4）被去掉，这条 parse 会真的把 READY 打回 EMBEDDING，
        // 本用例就会红 —— 这才是"守卫"的可证伪形式（若用一行无文件的合成 READY，缺文件会抛异常回滚，
        // 状态照样是 READY ⇒ 断言无法被最小变异打红，等于没断言）。
        long readyId = uploadId(TENANT, "ready.md", mdWith(25, "就绪"));
        awaitUntil(Duration.ofSeconds(20), () -> "EMBEDDING".equals(statusOf(readyId)), readyId);
        kbDocumentMapper.update(null, new LambdaUpdateWrapper<KbDocumentEntity>()
                .eq(KbDocumentEntity::getId, readyId).set(KbDocumentEntity::getStatus, "READY"));

        publishParse(readyId);

        // 哨兵：另一份文档的 parse 消息排在队尾。消费者是单容器顺序处理，哨兵走到 EMBEDDING 就证明
        // readyId 那条**已经被处理并 ack** —— 这比"睡一觉再看"既稳又不赌调度。
        long sentinel = uploadId(TENANT, "sentinel.md", mdWith(25, "哨兵"));
        publishParse(sentinel);
        awaitUntil(Duration.ofSeconds(20), () -> "EMBEDDING".equals(statusOf(sentinel)), sentinel);

        assertThat(statusOf(readyId))
                .as("READY 的消息必须被 ack 丢弃：状态绝不打回 EMBEDDING/PARSING")
                .isEqualTo("READY");
    }

    // ---------------------------------------------------------------- HTTP / DB 助手

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

    private long uploadId(long tenantId, String filename, byte[] content) throws Exception {
        ResponseEntity<String> res = postMultipart("/api/kb/documents", tenantId, filename, content);
        assertThat(res.getStatusCode()).as("上传必须 200（响应体=%s）", res.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode data = body(res).path("data");
        long id = data.path("id").asLong();
        assertThat(id).as("data.id 必须存在（响应体=%s）", res.getBody()).isPositive();
        return id;
    }

    private void publishParse(long docId) {
        rabbitTemplate.convertAndSend(KbTopology.EXCHANGE, KbTopology.PARSE_ROUTING_KEY,
                KbMessageCodec.parse(docId),
                (MessagePostProcessor) message -> {
                    message.getMessageProperties().setContentType(KbTopology.MESSAGE_CONTENT_TYPE);
                    return message;
                });
    }

    private String statusOf(long docId) {
        KbDocumentEntity row = kbDocumentMapper.selectById(docId);
        return row == null ? null : row.getStatus();
    }

    private long chunkRowsFor(long docId) {
        return kbChunkMapper.selectCount(new LambdaQueryWrapper<KbChunkEntity>()
                .eq(KbChunkEntity::getDocId, docId));
    }

    private long embeddedCountFor(long docId) {
        return kbChunkMapper.selectCount(new LambdaQueryWrapper<KbChunkEntity>()
                .eq(KbChunkEntity::getDocId, docId)
                .isNotNull(KbChunkEntity::getEmbeddedAt));
    }

    /**
     * 从 {@code kb.embed} 队列里收**属于本 doc**的批次，收到 {@code expected} 条或超时为止。
     * 队列是共享的（可能残留别的用例的消息）⇒ 不是本 doc 的一律丢弃，绝不"取第一条就断言"。
     */
    private List<KbEmbedBatch> awaitEmbedBatches(long docId, int expected, Duration timeout)
            throws InterruptedException {
        List<KbEmbedBatch> matched = new ArrayList<>();
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline && matched.size() < expected) {
            Message message = rabbitTemplate.receive(KbTopology.EMBED_QUEUE, 500);
            if (message == null) {
                continue;
            }
            String payload = new String(message.getBody(), UTF_8);
            if (!payload.startsWith("embed:")) {
                continue;
            }
            KbEmbedBatch batch;
            try {
                batch = KbMessageCodec.decodeEmbed(payload);
            } catch (IllegalArgumentException e) {
                continue;   // 解不开的残留消息：丢弃（本用例只关心自己的 doc）
            }
            if (batch.docId() == docId) {
                matched.add(batch);
            }
        }
        return matched;
    }

    /** 有界轮询（不许 sleep 硬等）；超时把当时的状态打进失败消息，而不是只说"超时了"。 */
    private void awaitUntil(Duration timeout, BooleanSupplier condition, long docId)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        Supplier<String> diagnostic = () -> "doc=" + docId + " status=" + statusOf(docId)
                + " chunks=" + chunkRowsFor(docId) + " embedded=" + embeddedCountFor(docId);
        fail("等待 " + timeout + " 仍未满足条件；当时的状态：" + diagnostic.get());
    }

    private static JsonNode body(ResponseEntity<String> res) throws Exception {
        return MAPPER.readTree(res.getBody());
    }

    private String token() {
        long now = Instant.now().getEpochSecond();
        return consoleTokenService.issue(new ConsoleClaims(1L, TENANT, ConsoleClaims.ROLE_ADMIN, now, now + 3_600L));
    }

    /**
     * 生成一篇长度**恰好 17000 字符**的 markdown。在 {@code size=800 / overlap=100}（步长 700）下，
     * 段数 = {@code ceil((17000-800)/700) + 1 = 25} ⇒ 每条解析消息产出恰好 25 段、3 个 embed 批次。
     * 长度精确可控是为了让 {@code 25} 这个断言**可复算**（数字必须指得到来源）。
     */
    private static byte[] mdWith(int paragraphs, String marker) {
        int targetLength = 17_000;
        StringBuilder sb = new StringBuilder(targetLength);
        for (int i = 0; i < paragraphs; i++) {
            sb.append("# ").append(marker).append(' ').append(i + 1).append('\n');
        }
        while (sb.length() < targetLength) {
            sb.append('x');
        }
        return sb.substring(0, targetLength).getBytes(UTF_8);
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
