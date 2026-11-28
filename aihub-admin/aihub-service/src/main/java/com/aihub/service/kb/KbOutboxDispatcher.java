package com.aihub.service.kb;

import com.aihub.dao.mapper.KbOutboxMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

/** 在独立调度线程中发布提交后的 outbox 消息。各次租约更新独立提交。 */
@Service
@ConditionalOnProperty(prefix = "aihub.kb.outbox", name = "enabled", matchIfMissing = true)
public class KbOutboxDispatcher {

    private final KbOutboxMapper outbox;
    private final KbDocumentPublisher publisher;

    public KbOutboxDispatcher(KbOutboxMapper outbox, KbDocumentPublisher publisher) {
        this.outbox = outbox;
        this.publisher = publisher;
    }

    /** 短暂领取单行租约后等待 RabbitMQ 确认，所有确认与退避写入均检验租约令牌。 */
    @Scheduled(fixedDelayString = "${aihub.kb.outbox.poll-delay:500ms}")
    public void dispatch() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("知识库 outbox 发布需要在事务外执行");
        }
        for (KbOutboxMapper.Entry entry : outbox.due()) {
            String token = UUID.randomUUID().toString();
            if (outbox.claim(entry.id(), token) == 0) {
                continue;
            }
            if (!publisher.publish(entry.routingKey(), entry.payload(), entry.generation())) {
                outbox.retry(entry.id(), token);
                return;
            }
            outbox.complete(entry.id(), token);
        }
    }
}
