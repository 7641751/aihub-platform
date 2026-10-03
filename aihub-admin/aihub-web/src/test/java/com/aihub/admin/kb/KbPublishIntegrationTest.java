package com.aihub.admin.kb;

import com.aihub.admin.support.AbstractIntegrationTest;
import com.aihub.dao.entity.KbDocumentEntity;
import com.aihub.dao.mapper.KbDocumentMapper;
import com.aihub.mq.kb.KbMessageCodec;
import com.aihub.mq.kb.KbTopology;
import com.aihub.service.console.ConsoleClaims;
import com.aihub.service.console.ConsoleTokenService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * M5 Task 3 的**线上契约**：上传事务**提交后**，一条 {@code parse:{docId}} 真的进了 {@code kb.parse} 队列。
 *
 * <p><b>真容器 + 真 HTTP + 真令牌 + 真 broker</b>，复用**已有**的 Spring 上下文（继承
 * {@link AbstractIntegrationTest}）。{@code @TestPropertySource} 那把字面量与 Task 2 的
 * {@code KbUploadIntegrationTest} / {@code ConsoleLoginIntegrationTest} **逐字相同**、且本类
 * **不加** {@code @Import} ⇒ 共用同一个上下文（判据：全量套件 {@code Tomcat started on port} 仍是 7）。
 *
 * <p><b>收消息用既有形状</b> {@code rabbitTemplate.receive(queue, timeoutMs)}（先例
 * {@code MeteringConsumerIntegrationTest:399/412}），**轮询到 deadline** —— 队列在共享的单例 broker 上，
 * 里面可能有别的用例的消息，所以绝不能"收一条就断言相等"；不是本次期望载荷的统统丢弃。
 *
 * <p><b>⚠️ timeout 必须为正</b>：{@code receive(queue, 0)} 在本项目的 Spring AMQP 版本上会抛
 * {@code ConsumeOkNotReceivedException}（"consumer failed to consume within 0 ms"）—— 计划给的
 * {@code while (receive(queue, 0) != null)} 形状**跑不通**，这里照既有先例用 {@code 200} 毫秒。
 * 抽干一个空队列的代价因此是 200ms（可忽略）。
 *
 * <p><b>{@code @BeforeEach} 必须先抽干 {@code kb.parse}</b>：Task 2 的上传用例**每次上传都会发一条**
 * {@code kb.parse}，残留会污染本用例的断言。
 *
 * <p><b>与 Task 4 的衔接（登记）</b>：Task 4 起，解析消费者会在**同一个 JVM** 里消费 {@code kb.parse}，
 * 本类"从队列收消息"的断言到那时会被消费者抢走 ⇒ Task 4+ 应以**数据库状态**（{@code kb_document.status}）
 * 为判据，而不是抢队列。
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
    private ConsoleTokenService consoleTokenService;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @BeforeEach
    @AfterEach
    void drainQueueAndCleanFixtures() {
        // 抽干 kb.parse：Task 2 的上传用例每次上传都会发一条，残留会让"收一条就断言"变成假红。
        // 照既有先例（MeteringConsumerIntegrationTest#drainDeadLetterQueue）用**正**超时：
        // receive(queue, 0) 会抛 ConsumeOkNotReceivedException，跑不通。
        while (rabbitTemplate.receive(KbTopology.PARSE_QUEUE, 200) != null) {
            // 丢弃历史遗留
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

        assertThat(awaitParseMessageFor(id, Duration.ofSeconds(10)))
                .as("提交后必须把 '%s' 发到 %s 队列", KbMessageCodec.parse(id), KbTopology.PARSE_QUEUE)
                .isTrue();
        assertThat(rowsFor(TENANT)).as("上传必须只建一行（定向查）").isEqualTo(1);
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

    /**
     * 轮询 {@code kb.parse} 直到**载荷等于** {@code parse:{docId}} 的消息出现（不是取收到的第一条）：
     * 队列在共享 broker 上，别的测试类若残留一条消息，"取第一条就断言"会变成一次假红。
     * 找不到就返回 {@code false}，由调用方断言红 —— 不允许把超时当成功。
     */
    private boolean awaitParseMessageFor(long docId, Duration timeout) throws InterruptedException {
        String expected = KbMessageCodec.parse(docId);
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            Message message = rabbitTemplate.receive(KbTopology.PARSE_QUEUE, 500);
            if (message != null && expected.equals(new String(message.getBody(), UTF_8))) {
                return true;
            }
        }
        return false;
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
