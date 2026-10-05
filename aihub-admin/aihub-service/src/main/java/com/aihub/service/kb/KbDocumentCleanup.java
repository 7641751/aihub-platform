package com.aihub.service.kb;

import com.aihub.dao.entity.KbChunkEntity;
import com.aihub.dao.mapper.KbChunkMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 失败文档的**清理**（M5 Task 6，决策 D8）：把一份文档已经写出去的**派生数据**全部收回。
 *
 * <p><b>顺序是契约</b>（D8）：① 删 Chroma（按 metadata {@code doc_id}）→ ② 删 {@code kb_chunk}。
 * 顺序反了会在「Chroma 删成功、但 {@code kb_chunk} 删失败」时留下**无法定位**的残留 ——
 * 坐标（{@code kb_chunk} 的 {@code vector_id = "{docId}:{seq}"}）正是事后重入清理的唯一手段，
 * 所以它必须**最后**才丢。
 *
 * <p><b>幂等可重入</b>：对已经干净的 doc 再清一次必须返回 {@link Result#ok()}，而不是抛异常 ——
 * 恢复器可能被调用多次（重投递 / 运维手工重放），"已经干净"是正常终态而不是错误。
 *
 * <p><b>绝不假装干净</b>（D8）：任一外部步失败（Chroma 不可达 / 非 2xx / 删完仍有记录）⇒
 * 返回 {@link Result#incomplete(String)} 并**保留 {@code kb_chunk}**；原因会被写进 {@code error_msg}
 * （由 {@link KbDocumentService#onTerminalFailure} 拼接）。这样"FAILED 但 Chroma 里还留着该 doc 的 chunk"
 * 这个违反不变量（D8）的情形**可被观测、可被重入修正**，而不是被静默掩盖。
 *
 * <p><b>没有 {@code @Transactional}</b>：与 {@code KbEmbedService} 同理 —— 第一步是**网络调用**
 * （Chroma），把它塞进数据库事务会让连接被网络等待占住；而 {@code kb_chunk} 的删除是**单条**语句，
 * 自带原子性。失败后的收敛靠"重入"而不是靠回滚。
 */
@Service
public class KbDocumentCleanup {

    private static final Logger log = LoggerFactory.getLogger(KbDocumentCleanup.class);

    private final KbVectorStoreClient vectorStore;
    private final KbChunkMapper kbChunkMapper;

    public KbDocumentCleanup(KbVectorStoreClient vectorStore, KbChunkMapper kbChunkMapper) {
        this.vectorStore = vectorStore;
        this.kbChunkMapper = kbChunkMapper;
    }

    /**
     * 清理结果。
     *
     * @param clean  {@code true} = Chroma 与 {@code kb_chunk} 都已清空（幂等终态）；{@code false} = 未完成
     * @param detail 未完成时的**非敏感**技术原因（会被写进 {@code error_msg}）；干净时为 {@code null}
     */
    public record Result(boolean clean, String detail) {

        public static Result ok() {
            return new Result(true, null);
        }

        public static Result incomplete(String detail) {
            return new Result(false, detail);
        }
    }

    /**
     * 清掉该 doc 在向量库与 {@code kb_chunk} 里的全部痕迹。
     *
     * @param docId {@code kb_document.id}
     * @return 是否已干净；未干净时 {@link Result#detail()} 给出原因
     */
    public Result cleanup(long docId) {
        // ① Chroma：按 metadata doc_id 全删（跨仓库契约，附录 A）。外部调用，放最前。
        try {
            vectorStore.deleteByDocId(docId);
            // 删除返回 200 不等于真的删干净（网络/实现都可能出岔子）：回查一次 ids ⇒ 绝不假装干净。
            int remaining = vectorStore.countByDocId(docId);
            if (remaining > 0) {
                log.warn("清理未完成：Chroma 删除后仍报告 doc {} 有 {} 条记录，保留 kb_chunk 以便重入",
                        docId, remaining);
                return Result.incomplete("向量库删除后仍有 " + remaining + " 条记录");
            }
        } catch (RuntimeException e) {
            log.warn("清理未完成：向量库删除失败，保留 kb_chunk 以便重入 docId={} ⇒ {}: {}",
                    docId, e.getClass().getSimpleName(), e.getMessage());
            return Result.incomplete("向量库删除失败：" + summarize(e));
        }

        // ② 只有**确认向量已清空**才删坐标（坐标是重入的定位手段，最后才丢）。
        kbChunkMapper.delete(new LambdaQueryWrapper<KbChunkEntity>().eq(KbChunkEntity::getDocId, docId));
        log.info("文档 {} 的向量与分段已清理干净", docId);
        return Result.ok();
    }

    /** 有界的异常摘要（类名 + 截断的消息）：清理失败的原因会被写进 {@code error_msg}，不能无限长。 */
    private static String summarize(Throwable cause) {
        if (cause == null) {
            return "<无异常>";
        }
        String message = cause.getMessage();
        String text = cause.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
        return text.length() > 160 ? text.substring(0, 160) + "…" : text;
    }
}
