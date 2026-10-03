package com.aihub.admin.kb;

import com.aihub.admin.kb.support.FakeEmbeddingUpstream;
import com.aihub.admin.kb.support.KbIntegrationTestBase;
import com.aihub.dao.entity.KbChunkEntity;
import com.aihub.dao.entity.KbDocumentEntity;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M5 Task 5 的**线上契约**：上传一篇 md，一路走到 {@code READY}，并且**向量真的在 Chroma 里**。
 *
 * <p><b>真容器（MySQL/Redis/RabbitMQ/**Chroma**）+ 真 HTTP + 真令牌 + 真 broker + 真消费者 + 真向量库</b>，
 * 唯一的假东西是 embeddings 上游（宿主进程内的 {@code FakeEmbeddingUpstream}，D6）。
 * 复用**已有** Spring 上下文（{@code @TestPropertySource} 那把字面量与其它 kb 用例**逐字相同**、
 * 且**不加** {@code @Import}）⇒ 判据是全量套件 {@code Tomcat started on port} 仍是 **7**。
 *
 * <p><b>"真的写进去了"只能用向量库本身证</b>（不是队列、也不是自报的状态）：所以这里直接用生产客户端
 * {@code KbVectorStoreClient} 查 Chroma 的 ids/metadatas。断言一律**按 docId 定向**（共享单例 Chroma 里
 * 会有别的用例的向量，全量计数会变成顺序依赖）。
 *
 * <p><b>两条"防假绿"的写法</b>（本项目 §8：不可证伪的断言不算断言）：
 * ① 重放用例先把状态与 {@code embedded_at} **手工退回**（否则 {@code EMBEDDING} 守卫会在写向量之前
 * 就 ack 丢弃 ⇒ 用例根本覆盖不到 upsert，变成空断言）；
 * ② 用一个**哨兵文档**证明"那条消息已被处理"，而不是睡一觉再看。
 */
@TestPropertySource(properties = {
        "aihub.console.secret=console-it-secret-0123456789abcdefghijklmn"
})
class KbEmbedIntegrationTest extends KbIntegrationTestBase {

    /** 本任务专用租户（与 Task 2/3/4 的 920001/920002/920003 区分）。 */
    private static final long TENANT = 920_004L;

    /** 17000 字符 ⇒ 在 size=800 / overlap=100（步长 700）下恰好 25 段（与 Task 4 的算法同一份）。 */
    private static final int CHUNKS = 25;

    @BeforeEach
    @AfterEach
    void resetUpstreamAndFixtures() {
        FakeEmbeddingUpstream.reset();
        cleanKbFixture(TENANT);
    }

    @Test
    void uploadingAMarkdownFileEndsUpQueryableInChroma() throws Exception {
        FakeEmbeddingUpstream.respondWithDeterministicVectors(8);

        long id = uploadId(TENANT, "notes.md", markdownOf(17_000, "段落"));

        awaitUntil(Duration.ofSeconds(90), () -> "READY".equals(statusOf(id)), id);

        assertThat(chunkRowsFor(id)).as("切分结果").isEqualTo(CHUNKS);
        assertThat(embeddedCountFor(id)).as("每一段都要有 embedded_at").isEqualTo(CHUNKS);
        assertThat(vectorStore.countByDocId(id)).as("★ 直接查 Chroma：真的写进去了").isEqualTo(CHUNKS);
        assertThat(seqsFromVectorIds(id))
                .as("seq 必须连续无缺，且**能从 vector_id 推出来**（D15：id 是可推导的坐标，不是随机数）")
                .containsExactlyElementsOf(IntStream.range(0, CHUNKS).boxed().toList());
        assertThat(vectorStore.tenantIdsForDoc(id))
                .as("metadata 的 tenant_id 必须正确（检索侧靠它做租户隔离，附录 A）")
                .containsExactly(TENANT);
    }

    @Test
    void aReplayedEmbedBatchDoesNotGrowTheVectorStore() throws Exception {
        FakeEmbeddingUpstream.respondWithDeterministicVectors(8);
        long id = uploadId(TENANT, "replay-embed.md", markdownOf(17_000, "重放"));
        awaitUntil(Duration.ofSeconds(90), () -> "READY".equals(statusOf(id)), id);

        int before = vectorStore.countByDocId(id);
        assertThat(before).isEqualTo(CHUNKS);

        // 让重放**真的走到写向量那一步**：退回最后一帧的状态与 embedded_at。
        // 不退回的话，EMBEDDING 守卫会在写之前 ack 丢弃 ⇒ 本用例覆盖不到 upsert（等于没断言）。
        cleanEmbeddedAtForLastBatch(id);
        assertThat(embeddedCountFor(id)).as("退回后必须是 20 段已嵌入（否则下面覆盖不到写向量）")
                .isEqualTo(CHUNKS - LAST_BATCH_SIZE);

        publishEmbed(id, CHUNKS - LAST_BATCH_SIZE, CHUNKS - 1);
        awaitUntil(Duration.ofSeconds(60), () -> "READY".equals(statusOf(id)), id);

        assertThat(vectorStore.countByDocId(id))
                .as("同一批重放 ⇒ 按 vector_id upsert **覆盖**，向量条数不变")
                .isEqualTo(before);
        assertThat(embeddedCountFor(id)).as("重放后仍然全打满").isEqualTo(CHUNKS);
    }

    @Test
    void anEmbedBatchForAnAlreadyReadyDocumentIsAckedAndIgnored() throws Exception {
        FakeEmbeddingUpstream.respondWithDeterministicVectors(8);
        long id = uploadId(TENANT, "ready-guard.md", markdownOf(17_000, "守卫"));
        awaitUntil(Duration.ofSeconds(90), () -> "READY".equals(statusOf(id)), id);

        int before = vectorStore.countByDocId(id);

        publishEmbed(id, 0, CHUNKS - 1);   // 该 doc 已是 READY ⇒ 必须被 ack 丢弃

        // 哨兵：另一篇文档走完整条链 ⇒ 它 READY 就证明上面那条**已经被处理过**
        // （同一队列、同一个消费者顺序处理；比"睡一觉再看"既稳又不赌调度）。
        long sentinel = uploadId(TENANT, "sentinel.md", markdownOf(17_000, "哨兵"));
        awaitUntil(Duration.ofSeconds(90), () -> "READY".equals(statusOf(sentinel)), sentinel);

        assertThat(statusOf(id)).as("READY 的文档绝不被打回 EMBEDDING/PARSING").isEqualTo("READY");
        assertThat(vectorStore.countByDocId(id)).as("也不许再写一遍向量").isEqualTo(before);
    }

    // ---------------------------------------------------------------- 助手

    private static final int LAST_BATCH_SIZE = 5;

    private void cleanEmbeddedAtForLastBatch(long docId) {
        kbChunkMapper.update(null, new LambdaUpdateWrapper<KbChunkEntity>()
                .eq(KbChunkEntity::getDocId, docId)
                .between(KbChunkEntity::getSeq, CHUNKS - LAST_BATCH_SIZE, CHUNKS - 1)
                .set(KbChunkEntity::getEmbeddedAt, null));
        kbDocumentMapper.update(null, new LambdaUpdateWrapper<KbDocumentEntity>()
                .eq(KbDocumentEntity::getId, docId)
                .set(KbDocumentEntity::getStatus, "EMBEDDING"));
    }

    /** 从 {@code vector_id}（{@code "{docId}:{seq}"}）里把 seq 解析出来并排序。 */
    private List<Integer> seqsFromVectorIds(long docId) {
        return vectorStore.idsForDoc(docId).stream()
                .map(id -> Integer.parseInt(id.substring(id.lastIndexOf(':') + 1)))
                .sorted()
                .toList();
    }
}
