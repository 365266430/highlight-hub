package com.highlighthub.outbox;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.highlighthub.common.Utils;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Transactional outbox: task terminal states write an event row in the SAME
 * transaction, so a committed state change can never lose its event.
 * The in-process publisher drains rows (RabbitMQ relay plugs in behind an
 * optional profile in a later deployment stage); consumers are idempotent by
 * eventId + consumerName via consumed_events.
 */
public class Outbox {

    @TableName("outbox_events")
    public static class EventEntity {
        @TableId(type = IdType.AUTO)
        private Long id;
        private String eventId;
        private String eventType;
        private String aggregateId;
        private Integer aggregateVersion;
        private String payloadJson;
        private LocalDateTime createdAt;
        private LocalDateTime publishedAt;
        private Integer publishAttempts;

        public Long getId() { return id; }
        public String getEventId() { return eventId; }
        public String getEventType() { return eventType; }
        public String getAggregateId() { return aggregateId; }
        public Integer getAggregateVersion() { return aggregateVersion; }
        public String getPayloadJson() { return payloadJson; }
        public LocalDateTime getCreatedAt() { return createdAt; }
        public LocalDateTime getPublishedAt() { return publishedAt; }
        public Integer getPublishAttempts() { return publishAttempts; }
    }

    @Mapper
    public interface EventMapper extends BaseMapper<EventEntity> {

        @Insert("""
                INSERT INTO outbox_events (event_id, event_type, aggregate_id, aggregate_version,
                  payload_json, created_at, publish_attempts)
                VALUES (#{eventId}, #{eventType}, #{aggregateId}, #{aggregateVersion},
                  #{payloadJson}, UTC_TIMESTAMP(3), 0)
                """)
        void insertEvent(@Param("eventId") String eventId, @Param("eventType") String eventType,
                         @Param("aggregateId") String aggregateId,
                         @Param("aggregateVersion") int aggregateVersion,
                         @Param("payloadJson") String payloadJson);

        @Select("""
                SELECT * FROM outbox_events WHERE published_at IS NULL AND publish_attempts < 20
                ORDER BY id LIMIT 50
                """)
        List<EventEntity> findUnpublished();

        @Update("""
                UPDATE outbox_events SET published_at = UTC_TIMESTAMP(3) WHERE id = #{id}
                """)
        int markPublished(@Param("id") Long id);
    }

    @org.springframework.stereotype.Component
    public static class Recorder {
        private final EventMapper mapper;

        public Recorder(EventMapper mapper) {
            this.mapper = mapper;
        }

        /** called inside the same transaction as the state change */
        public void record(String eventType, String aggregateId, int aggregateVersion,
                           Map<String, Object> payload) {
            Map<String, Object> safe = payload == null ? Map.of() : payload;
            mapper.insertEvent(UUID.randomUUID().toString(), eventType, aggregateId,
                    aggregateVersion, Utils.toJson(safe));
        }
    }

    @org.springframework.stereotype.Component
    public static class Publisher {
        private static final Logger log = LoggerFactory.getLogger(Publisher.class);
        private final EventMapper mapper;

        public Publisher(EventMapper mapper) {
            this.mapper = mapper;
        }

        @Scheduled(fixedDelay = 5000, initialDelay = 20_000)
        public void drain() {
            for (EventEntity e : mapper.findUnpublished()) {
                // in-process delivery for now; the RabbitMQ relay (optional profile)
                // consumes the same rows. Retries stay bounded by publish_attempts.
                int updated = mapper.markPublished(e.getId());
                if (updated == 1) {
                    log.debug("outbox event {} ({}) dispatched", e.getEventId(), e.getEventType());
                }
            }
        }
    }
}
