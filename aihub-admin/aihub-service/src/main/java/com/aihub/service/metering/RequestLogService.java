package com.aihub.service.metering;

import com.aihub.common.meter.MeteringEvent;
import com.aihub.dao.entity.RequestLogEntity;
import com.aihub.dao.mapper.RequestLogMapper;
import com.aihub.mq.meter.MeteringSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 计量事件落 {@code request_log}。真相源（派生数据可重建，但重建需要它）。
 *
 * <p><b>幂等</b>：唯一键 {@code (request_id, created_at)} 就是设计文档 §8.3 的「request_id 唯一键，
 * 重复消费直接丢弃」。因此 {@code created_at} **必须**取事件里由网关生成的值：
 * 用 {@code now()} 会让每一次重投都变成新行（实测：created_at 差 1 毫秒即两行）。
 *
 * <p>只吞 {@link DuplicateKeyException}；其它 {@code DataAccessException}（列超长、约束、连接失败）
 * 一律抛出，让消息重试/进死信 —— 那些不是「重复消费」，静默吞掉就等于丢数据。
 *
 * <p><b>注意</b>：这个 catch 假定 {@code request_log} 上只有一个期望的唯一键
 * （{@link #ASSUMED_IDEMPOTENCY_UNIQUE_KEY}）。给该表新增唯一索引前，务必先读那段注释。
 */
@Service
public class RequestLogService implements MeteringSink {

    private static final Logger log = LoggerFactory.getLogger(RequestLogService.class);

    /**
     * 本类幂等语义所假定的**唯一**唯一键，对应 V1 的
     * {@code UNIQUE KEY uk_request_log_request_id (request_id, created_at)}。
     *
     * <p>下面的 {@code catch (DuplicateKeyException)} 把「任何重复键冲突」都解释成「重复消费」；
     * 这只有在 {@code request_log} 上**恰好只有这一个**唯一索引时才成立。
     * 将来若给该表再加唯一索引（例如 (request_id) 或 (tenant_id, created_at)），一种**全新的**冲突
     * 也会被静默吞成 false → 丢事件。届时必须同步修改这里的映射（按约束名区分，或改成先查后写）。
     */
    private static final String ASSUMED_IDEMPOTENCY_UNIQUE_KEY =
            "uk_request_log_request_id (request_id, created_at)";

    private final RequestLogMapper requestLogMapper;

    public RequestLogService(RequestLogMapper requestLogMapper) {
        this.requestLogMapper = requestLogMapper;
    }

    @Override
    public boolean persist(MeteringEvent event) {
        RequestLogEntity entity = new RequestLogEntity();
        entity.setRequestId(event.requestId());
        entity.setTenantId(event.tenantId());
        entity.setApiKeyId(event.apiKeyId());
        entity.setChannelId(event.channelId());
        entity.setModel(event.model());
        entity.setPromptTokens(event.promptTokens());
        entity.setCompletionTokens(event.completionTokens());
        entity.setTotalTokens(event.totalTokens());
        entity.setLatencyMs(event.latencyMs());
        entity.setTtftMs(event.ttftMs());
        entity.setStatus(event.status());
        entity.setErrorCode(event.errorCode());
        // 显式 UTC：分区列与幂等键都按 UTC 墙上时间比较（CONVENTIONS §7）。
        entity.setCreatedAt(LocalDateTime.ofInstant(event.createdAt(), ZoneOffset.UTC));
        try {
            requestLogMapper.insert(entity);
            return true;
        } catch (DuplicateKeyException e) {
            // 这里只做「重复键 ⇒ 幂等重投」的映射：DuplicateKeyException 不带索引名，
            // 因此**它假定冲突来自 ASSUMED_IDEMPOTENCY_UNIQUE_KEY**（见该常量）。
            log.debug("计量事件重复（假定冲突键 {}，幂等键 request_id={} created_at={}），丢弃",
                    ASSUMED_IDEMPOTENCY_UNIQUE_KEY, event.requestId(), entity.getCreatedAt());
            return false;
        }
    }
}
