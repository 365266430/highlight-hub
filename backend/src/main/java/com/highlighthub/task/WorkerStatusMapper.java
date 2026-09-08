package com.highlighthub.task;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface WorkerStatusMapper {

    @Insert("""
            INSERT INTO worker_status (worker_id, last_heartbeat, active_task_id, disk_free_bytes,
              uptime_seconds, created_at, updated_at)
            VALUES (#{workerId}, UTC_TIMESTAMP(3), #{activeTaskId}, #{diskFreeBytes}, #{uptimeSeconds},
              UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
            ON DUPLICATE KEY UPDATE last_heartbeat = UTC_TIMESTAMP(3),
              active_task_id = VALUES(active_task_id), disk_free_bytes = VALUES(disk_free_bytes),
              uptime_seconds = VALUES(uptime_seconds), updated_at = UTC_TIMESTAMP(3)
            """)
    void upsertHeartbeat(@Param("workerId") String workerId, @Param("activeTaskId") String activeTaskId,
                         @Param("diskFreeBytes") Long diskFreeBytes, @Param("uptimeSeconds") Long uptimeSeconds);

    /** a worker is considered online when it pinged within the last 60 seconds */
    @Select("""
            SELECT worker_id, active_task_id, disk_free_bytes, uptime_seconds,
              (last_heartbeat >= UTC_TIMESTAMP(3) - INTERVAL 60 SECOND) online,
              last_heartbeat
            FROM worker_status ORDER BY last_heartbeat DESC
            """)
    List<Map<String, Object>> listStatus();
}
