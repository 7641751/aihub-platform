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
 */
@Service
public class RequestLogService implements MeteringSink {

    private static final Logger log = LoggerFactory.getLogger(RequestLogService.class);

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
            log.debug("计量事件重复（幂等键 request_id={} created_at={}），丢弃",
                    event.requestId(), entity.getCreatedAt());
            return false;
        }
    }
}
