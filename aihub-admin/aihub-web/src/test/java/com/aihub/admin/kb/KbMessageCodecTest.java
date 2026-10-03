package com.aihub.admin.kb;

import com.aihub.mq.kb.KbEmbedBatch;
import com.aihub.mq.kb.KbMessageCodec;
import com.aihub.mq.kb.KbTopology;
import com.aihub.mq.kb.KbTopologyConfig;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M5 Task 3 的**零上下文单测**：kb 消息的线格式（固定向量）、分批纯函数、拓扑声明（死信）。
 *
 * <p><b>为什么放在 {@code aihub-web} 而不是 {@code aihub-mq}</b>：{@code aihub-mq} 没有
 * {@code src/test}、其 {@code pom.xml} 也没有 {@code spring-boot-starter-test}（实测）——把测试放那边
 * **编译不过**。{@code aihub-web} 经 {@code aihub-service → aihub-mq} 的传递依赖能看到这些类。
 *
 * <p><b>本类刻意不启 Spring</b>（纯 JUnit + AssertJ）：{@code KbTopologyConfig} 用 {@code new} 直接实例化，
 * 因此**不占 Spring 上下文预算**（套件里 {@code Tomcat started on port} 必须保持 7）。
 */
class KbMessageCodecTest {

    // ---------------------------------------------------------------- 1) 线格式固定向量

    /** 解析消息的线格式：{@code parse:{docId}}，文本分隔符、无 JSON（与 {@code MeteringEventCodec} 同风格）。 */
    @Test
    void parsePayloadIsColonDelimitedTextAndRoundTrips() {
        assertThat(KbMessageCodec.parse(7L)).isEqualTo("parse:7");
        assertThat(KbMessageCodec.decodeParse("parse:7")).isEqualTo(7L);
    }

    /** 嵌入消息的线格式：{@code embed:{docId}:{seqFrom}:{seqTo}}，用**区间**表达批次（D10）。 */
    @Test
    void embedPayloadCarriesTheBatchRangeAndRoundTrips() {
        assertThat(KbMessageCodec.embed(7L, 0, 9)).isEqualTo("embed:7:0:9");
        assertThat(KbMessageCodec.decodeEmbed("embed:7:0:9")).isEqualTo(new KbEmbedBatch(7L, 0, 9));
    }

    // ---------------------------------------------------------------- 2) 畸形/错路由载荷必须抛（进 DLQ）

    /**
     * 错路由的消息必须**响亮失败**：静默 ACK 一条解不开的消息等于永久丢数据（照 {@code MeteringConsumer}
     * 的纪律）。判据是「抛 {@link IllegalArgumentException}」，而不是「返回 null / 0」。
     */
    @Test
    void wrongKindPayloadsFailLoudlySoTheyLandInTheDeadLetterQueue() {
        assertThatThrownBy(() -> KbMessageCodec.decodeParse("embed:7:0:9"))
                .as("错路由的消息必须响亮失败，才能进 DLQ")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KbMessageCodec.decodeEmbed("parse:7"))
                .as("错路由的消息必须响亮失败，才能进 DLQ")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void malformedParsePayloadsFailLoudly() {
        String[] bad = {null, "", "parse:", "parse:abc", "parse:7:8", "preamble:7", "parse:7 ", " parse:7"};
        for (String payload : bad) {
            assertThatThrownBy(() -> KbMessageCodec.decodeParse(payload))
                    .as("畸形解析载荷必须抛（不是静默丢）：%s", payload)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void malformedEmbedPayloadsFailLoudly() {
        String[] bad = {null, "", "embed:7:0", "embed:7:0:9:1", "embed:x:0:9", "embed:7:a:9",
                "embed:7:9:0", "preamble:7:0:9"};
        for (String payload : bad) {
            assertThatThrownBy(() -> KbMessageCodec.decodeEmbed(payload))
                    .as("畸形嵌入载荷必须抛（不是静默丢）：%s", payload)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ---------------------------------------------------------------- 3) 分批纯函数（D10）

    /** 25 段 / 每批 10 ⇒ 3 批，**最后一批可以不满**（seqFrom 0,10,20；末批 seqTo=24）。 */
    @Test
    void embedBatchesSplitsIntoFixedSizeRangesWithAPartialTail() {
        assertThat(KbTopology.embedBatches(7L, 25, 10))
                .extracting(KbEmbedBatch::seqFrom).containsExactly(0, 10, 20);
        assertThat(KbTopology.embedBatches(7L, 25, 10))
                .extracting(KbEmbedBatch::seqTo).containsExactly(9, 19, 24);
        assertThat(KbTopology.embedBatches(7L, 25, 10))
                .allSatisfy(b -> assertThat(b.docId()).isEqualTo(7L));
    }

    /** 0 段不该走到这里（Task 4 会先判 `FAILED("无可提取文本")`，D14）⇒ 返回空列表而不是 1 条空批。 */
    @Test
    void embedBatchesOfZeroChunksIsEmpty() {
        assertThat(KbTopology.embedBatches(7L, 0, 10))
                .as("0 段不该走到这里（Task 4 会先判 FAILED）").isEmpty();
    }

    /** 批次大小必须为正：0/负会让 `ceil` 除零或死循环，所以**快速失败**。 */
    @Test
    void embedBatchesRejectsNonPositiveBatchSize() {
        assertThatThrownBy(() -> KbTopology.embedBatches(7L, 5, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KbTopology.embedBatches(7L, 5, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------- 4) 拓扑声明（两个业务队列都挂死信）

    /**
     * 两个业务队列**都**必须挂 {@code x-dead-letter-exchange} + routing key（裁定 #6 / 验收判据）。
     * 只挂一个会让那个阶段的坏消息无限 requeue 把 broker 与日志同时打爆。
     */
    @Test
    void bothBusinessQueuesDeclareADeadLetterExchange() {
        KbTopologyConfig config = new KbTopologyConfig();

        assertThat(config.kbParseQueue().getArguments())
                .as("解析队列必须挂死信")
                .containsEntry("x-dead-letter-exchange", KbTopology.DEAD_LETTER_EXCHANGE)
                .containsEntry("x-dead-letter-routing-key", KbTopology.DEAD_LETTER_ROUTING_KEY);
        assertThat(config.kbEmbedQueue().getArguments())
                .as("嵌入队列必须挂死信")
                .containsEntry("x-dead-letter-exchange", KbTopology.DEAD_LETTER_EXCHANGE)
                .containsEntry("x-dead-letter-routing-key", KbTopology.DEAD_LETTER_ROUTING_KEY);
    }

    /**
     * 声明的名字与路由键**逐字**取自 {@link KbTopology}（常量是唯一真相源：单侧改名不会编译失败，
     * 只会变成「消息永远投不到队列」，因此这里把「声明 == 常量」钉住）。
     */
    @Test
    void declaredNamesAndRoutingKeysMatchTheTopologyConstants() {
        KbTopologyConfig config = new KbTopologyConfig();

        assertThat(config.kbExchange().getName()).isEqualTo(KbTopology.EXCHANGE);
        assertThat(config.kbParseQueue().getName()).isEqualTo(KbTopology.PARSE_QUEUE);
        assertThat(config.kbEmbedQueue().getName()).isEqualTo(KbTopology.EMBED_QUEUE);
        assertThat(config.kbDeadLetterExchange().getName()).isEqualTo(KbTopology.DEAD_LETTER_EXCHANGE);
        assertThat(config.kbDeadLetterQueue().getName()).isEqualTo(KbTopology.DEAD_LETTER_QUEUE);

        Binding parseBinding = config.kbParseBinding(config.kbParseQueue(), config.kbExchange());
        assertThat(parseBinding.getRoutingKey()).isEqualTo(KbTopology.PARSE_ROUTING_KEY);
        Binding embedBinding = config.kbEmbedBinding(config.kbEmbedQueue(), config.kbExchange());
        assertThat(embedBinding.getRoutingKey()).isEqualTo(KbTopology.EMBED_ROUTING_KEY);
        Binding deadLetterBinding =
                config.kbDeadLetterBinding(config.kbDeadLetterQueue(), config.kbDeadLetterExchange());
        assertThat(deadLetterBinding.getRoutingKey()).isEqualTo(KbTopology.DEAD_LETTER_ROUTING_KEY);
    }

    /** 队列必须是 durable 的（重启后拓扑仍在，消息不丢），且解析/嵌入是**两条**独立队列（D16）。 */
    @Test
    void theBusinessQueuesAreDurableAndDistinct() {
        KbTopologyConfig config = new KbTopologyConfig();
        Queue parse = config.kbParseQueue();
        Queue embed = config.kbEmbedQueue();
        assertThat(parse.isDurable()).as("解析队列必须 durable").isTrue();
        assertThat(embed.isDurable()).as("嵌入队列必须 durable").isTrue();
        assertThat(parse.getName()).as("解析与嵌入必须是两条队列").isNotEqualTo(embed.getName());
    }
}
