package com.aihub.dao.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** 持久化文档状态变更产生的消息，并用到期时间和租约令牌协调发布者。 */
public interface KbOutboxMapper {

    /** 与文档状态变更使用同一事务。重复入队保留原有退避与租约。 */
    @Insert("""
            INSERT INTO kb_outbox (doc_id, generation, routing_key, payload)
            VALUES (#{docId}, #{generation}, #{routingKey}, #{payload})
            ON DUPLICATE KEY UPDATE id = id
            """)
    int enqueue(@Param("docId") long docId, @Param("generation") long generation,
                @Param("routingKey") String routingKey, @Param("payload") String payload);

    /** 提供发布内容及主键。行的发布权由后续条件更新取得。 */
    record Entry(long id, long generation, String routingKey, String payload) {
    }

    @Select("""
            SELECT id, generation, routing_key, payload
            FROM kb_outbox
            WHERE available_at <= UTC_TIMESTAMP(3)
            ORDER BY available_at, id
            LIMIT 16
            """)
    List<Entry> due();

    @Update("""
            UPDATE kb_outbox
            SET lease_token = #{token}, available_at = TIMESTAMPADD(SECOND, 30, UTC_TIMESTAMP(3))
            WHERE id = #{id} AND available_at <= UTC_TIMESTAMP(3)
            """)
    int claim(@Param("id") long id, @Param("token") String token);

    @Delete("DELETE FROM kb_outbox WHERE id = #{id} AND lease_token = #{token}")
    int complete(@Param("id") long id, @Param("token") String token);

    /** 根据失败次数退避，依次等待 2、4、8、16、32、60 秒，之后继续以 60 秒为间隔重试。 */
    @Update("""
            UPDATE kb_outbox
            SET available_at = TIMESTAMPADD(SECOND, LEAST(60, 2 << LEAST(failures, 5)), UTC_TIMESTAMP(3)),
                failures = failures + 1,
                lease_token = NULL
            WHERE id = #{id} AND lease_token = #{token}
            """)
    int retry(@Param("id") long id, @Param("token") String token);
}
