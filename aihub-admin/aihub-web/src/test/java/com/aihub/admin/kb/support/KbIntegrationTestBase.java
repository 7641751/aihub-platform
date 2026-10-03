package com.aihub.admin.kb.support;

import com.aihub.admin.support.AbstractIntegrationTest;
import com.aihub.dao.entity.KbChunkEntity;
import com.aihub.dao.entity.KbDocumentEntity;
import com.aihub.dao.mapper.KbChunkMapper;
import com.aihub.dao.mapper.KbDocumentMapper;
import com.aihub.mq.kb.KbTopology;
import com.aihub.service.console.ConsoleClaims;
import com.aihub.service.console.ConsoleTokenService;
import com.aihub.service.kb.KbVectorStoreClient;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * kb 集成测试的**助手基类**（M5 Task 5）。
 *
 * <p><b>⚠️ 本类刻意不带任何 Spring 注解</b>：注解（尤其 {@code @DynamicPropertySource} /
 * {@code @TestPropertySource} / {@code @Import}）会进入 Spring 的**上下文缓存键**，于是每个子类都会
 * fork 一个**新上下文** —— 那会打破 M5 的硬约束（全量 {@code Tomcat started on port} 必须保持 **7**）。
 * 容器与属性都由 {@code TestContainers} + {@link AbstractIntegrationTest} 那**一处**提供；
 * 本类只放"测试之间的公共代码"（HTTP 助手、DB 查询、有界轮询）。
 *
 * <p><b>断言一律走数据库 + 向量库**状态**</b>，不抢 {@code kb.parse}/{@code kb.embed} 队列：
 * 每个 Spring 上下文里都跑着两个消费者，而且它们**争抢**同一个共享队列 ⇒ 抢队列必然 flaky。
 * "真的写进 Chroma 了"这条，用生产客户端 {@link KbVectorStoreClient} 直接查（那是外部事实源，不是队列）。
 */
public abstract class KbIntegrationTestBase extends AbstractIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    protected TestRestTemplate restTemplate;

    @Autowired
    protected KbDocumentMapper kbDocumentMapper;

    @Autowired
    protected KbChunkMapper kbChunkMapper;

    @Autowired
    protected ConsoleTokenService consoleTokenService;

    @Autowired
    protected RabbitTemplate rabbitTemplate;

    /** 生产客户端：测试用它**直接查 Chroma**（"真的写进去了"只能这么证）。 */
    @Autowired
    protected KbVectorStoreClient vectorStore;

    /** 让 {@code ByteArrayResource.getFilename()} 返回真实文件名（multipart 的 {@code file} part 要带名字）。 */
    protected static final class NamedByteArrayResource extends ByteArrayResource {

        private final String filename;

        public NamedByteArrayResource(byte[] bytes, String filename) {
            super(bytes);
            this.filename = filename;
        }

        @Override
        public String getFilename() {
            return filename;
        }
    }

    protected ResponseEntity<String> postMultipart(String path, Long tenantId, String filename, byte[] content) {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        if (tenantId != null) {
            parts.add("tenantId", String.valueOf(tenantId));
        }
        parts.add("file", new NamedByteArrayResource(content, filename));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(token(tenantId == null ? 1L : tenantId));
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(parts, headers), String.class);
    }

    protected long uploadId(long tenantId, String filename, byte[] content) {
        ResponseEntity<String> res = postMultipart("/api/kb/documents", tenantId, filename, content);
        assertThat(res.getStatusCode()).as("上传必须 200（响应体=%s）", res.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode data = body(res).path("data");
        long id = data.path("id").asLong();
        assertThat(id).as("data.id 必须存在（响应体=%s）", res.getBody()).isPositive();
        return id;
    }

    /** 直接投一条 {@code parse:{docId}}（绕开上传，聚焦本阶段的判据）。 */
    protected void publishParse(long docId) {
        send(KbTopology.PARSE_ROUTING_KEY, com.aihub.mq.kb.KbMessageCodec.parse(docId));
    }

    /** 直接投一条 {@code embed:{docId}:{from}:{to}}（重放用例用它）。 */
    protected void publishEmbed(long docId, int seqFrom, int seqTo) {
        send(KbTopology.EMBED_ROUTING_KEY, com.aihub.mq.kb.KbMessageCodec.embed(docId, seqFrom, seqTo));
    }

    private void send(String routingKey, String payload) {
        rabbitTemplate.convertAndSend(KbTopology.EXCHANGE, routingKey, payload,
                (MessagePostProcessor) message -> {
                    message.getMessageProperties().setContentType(KbTopology.MESSAGE_CONTENT_TYPE);
                    return message;
                });
    }

    protected String statusOf(long docId) {
        KbDocumentEntity row = kbDocumentMapper.selectById(docId);
        return row == null ? null : row.getStatus();
    }

    protected long chunkRowsFor(long docId) {
        return kbChunkMapper.selectCount(new LambdaQueryWrapper<KbChunkEntity>()
                .eq(KbChunkEntity::getDocId, docId));
    }

    protected long embeddedCountFor(long docId) {
        return kbChunkMapper.selectCount(new LambdaQueryWrapper<KbChunkEntity>()
                .eq(KbChunkEntity::getDocId, docId)
                .isNotNull(KbChunkEntity::getEmbeddedAt));
    }

    /** 有界轮询（不许 sleep 硬等）；超时把当时的状态打进失败消息，而不是只说"超时了"。 */
    protected void awaitUntil(Duration timeout, BooleanSupplier condition, long docId) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        Supplier<String> diagnostic = () -> "doc=" + docId + " status=" + statusOf(docId)
                + " chunks=" + chunkRowsFor(docId) + " embedded=" + embeddedCountFor(docId)
                + " vectors=" + vectorStore.countByDocId(docId);
        fail("等待 " + timeout + " 仍未满足条件；当时的状态：" + diagnostic.get());
    }

    /** 按租户清夹具：先删 kb_chunk（没有外键），再删 kb_document，最后清向量库里这些 doc 的记录。 */
    protected void cleanKbFixture(long tenantId) {
        List<Long> docIds = kbDocumentMapper.selectList(new LambdaQueryWrapper<KbDocumentEntity>()
                        .eq(KbDocumentEntity::getTenantId, tenantId))
                .stream().map(KbDocumentEntity::getId).toList();
        if (!docIds.isEmpty()) {
            kbChunkMapper.delete(new LambdaQueryWrapper<KbChunkEntity>().in(KbChunkEntity::getDocId, docIds));
        }
        kbDocumentMapper.delete(new LambdaQueryWrapper<KbDocumentEntity>()
                .eq(KbDocumentEntity::getTenantId, tenantId));
        docIds.forEach(vectorStore::deleteByDocId);
    }

    protected static JsonNode body(ResponseEntity<String> res) {
        try {
            return MAPPER.readTree(res.getBody());
        } catch (Exception e) {
            throw new IllegalStateException("响应不是合法 JSON：" + res.getBody(), e);
        }
    }

    protected String token(long tenantId) {
        long now = Instant.now().getEpochSecond();
        return consoleTokenService.issue(new ConsoleClaims(1L, tenantId, ConsoleClaims.ROLE_ADMIN, now, now + 3_600L));
    }

    /** 生成一篇长度**恰好 17000 字符**的 markdown：在 size=800 / overlap=100（步长 700）下恰好 25 段（见 Task 4 的算法）。 */
    protected static byte[] markdownOf(int targetLength, String marker) {
        StringBuilder sb = new StringBuilder(targetLength);
        sb.append("# ").append(marker).append('\n');
        while (sb.length() < targetLength) {
            sb.append('x');
        }
        return sb.substring(0, targetLength).getBytes(UTF_8);
    }
}
