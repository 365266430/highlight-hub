package com.highlighthub.task;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@org.apache.ibatis.annotations.Mapper
public interface TaskMapper extends com.baomidou.mybatisplus.core.mapper.BaseMapper<TaskEntity> {

    /** Atomic claim: SKIP LOCKED avoids two workers fighting over the same rows. */
    @Select("""
            <script>
            SELECT * FROM tasks
            WHERE status = 'QUEUED' AND next_run_at &lt;= UTC_TIMESTAMP(3)
              AND type IN
              <foreach item='t' collection='types' open='(' separator=',' close=')'>#{t}</foreach>
            ORDER BY next_run_at, created_at
            LIMIT #{limit}
            FOR UPDATE SKIP LOCKED
            </script>
            """)
    List<TaskEntity> selectQueuedForUpdate(@Param("types") List<String> types, @Param("limit") int limit);

    /** mark RUNNING + new token, guarded so only the claim transaction's rows update */
    @Update("""
            UPDATE tasks SET status = 'RUNNING', attempt = attempt + 1, attempt_token = #{token},
              worker_id = #{workerId}, lease_until = #{leaseUntil}, started_at = COALESCE(started_at, UTC_TIMESTAMP(3)),
              progress = 0, phase = NULL, error_code = NULL, error_message = NULL,
              cancel_requested_at = NULL, updated_at = UTC_TIMESTAMP(3)
            WHERE id = #{taskId} AND status = 'QUEUED'
            """)
    int markRunning(@Param("taskId") String taskId, @Param("token") String token,
                    @Param("workerId") String workerId, @Param("leaseUntil") LocalDateTime leaseUntil);

    /** every worker callback validates against the live token + RUNNING state */
    @Update("""
            UPDATE tasks SET lease_until = #{leaseUntil}, updated_at = UTC_TIMESTAMP(3)
            WHERE id = #{taskId} AND attempt_token = #{token} AND status = 'RUNNING'
            """)
    int renewLease(@Param("taskId") String taskId, @Param("token") String token,
                   @Param("leaseUntil") LocalDateTime leaseUntil);

    @Update("""
            UPDATE tasks SET progress = #{progress}, phase = #{phase}, lease_until = #{leaseUntil},
              updated_at = UTC_TIMESTAMP(3)
            WHERE id = #{taskId} AND attempt_token = #{token} AND status = 'RUNNING'
            """)
    int updateProgress(@Param("taskId") String taskId, @Param("token") String token,
                       @Param("progress") int progress, @Param("phase") String phase,
                       @Param("leaseUntil") LocalDateTime leaseUntil);

    /** idempotent success: only the current attempt on a RUNNING task can complete it */
    @Update("""
            UPDATE tasks SET status = 'SUCCEEDED', output_ref = #{outputRef}, progress = 100,
              finished_at = UTC_TIMESTAMP(3), updated_at = UTC_TIMESTAMP(3), lease_until = NULL
            WHERE id = #{taskId} AND attempt_token = #{token} AND status = 'RUNNING'
            """)
    int markSucceeded(@Param("taskId") String taskId, @Param("token") String token,
                      @Param("outputRef") String outputRef);

    /** cancel confirm: from CANCEL_REQUESTED only, regardless of token (worker may have lost lease) */
    @Update("""
            UPDATE tasks SET status = 'CANCELLED', finished_at = UTC_TIMESTAMP(3),
              updated_at = UTC_TIMESTAMP(3), lease_until = NULL
            WHERE id = #{taskId} AND status = 'CANCEL_REQUESTED'
            """)
    int markCancelled(@Param("taskId") String taskId);

    @Update("""
            UPDATE tasks SET status = #{nextStatus}, error_code = #{errorCode}, error_message = #{errorMessage},
              next_run_at = COALESCE(#{nextRunAt}, UTC_TIMESTAMP(3)), attempt_token = NULL, worker_id = NULL,
              lease_until = NULL, finished_at = #{finishNow}, updated_at = UTC_TIMESTAMP(3)
            WHERE id = #{taskId} AND attempt_token = #{token} AND status = 'RUNNING'
            """)
    int finishAttempt(@Param("taskId") String taskId, @Param("token") String token,
                      @Param("nextStatus") String nextStatus, @Param("errorCode") String errorCode,
                      @Param("errorMessage") String errorMessage,
                      @Param("nextRunAt") LocalDateTime nextRunAt, @Param("finishNow") LocalDateTime finishNow);

    /** user requests cancel: QUEUED dies immediately; RUNNING asks the worker to stop */
    @Update("""
            UPDATE tasks SET status = 'CANCEL_REQUESTED', cancel_requested_at = UTC_TIMESTAMP(3),
              updated_at = UTC_TIMESTAMP(3)
            WHERE id = #{taskId} AND status = 'RUNNING'
            """)
    int requestCancelRunning(@Param("taskId") String taskId);

    @Update("""
            UPDATE tasks SET status = 'CANCELLED', finished_at = UTC_TIMESTAMP(3), updated_at = UTC_TIMESTAMP(3)
            WHERE id = #{taskId} AND status = 'QUEUED'
            """)
    int cancelQueued(@Param("taskId") String taskId);

    /** lease sweep: reclaim dead-worker RUNNING tasks back to QUEUED with backoff */
    @Select("""
            SELECT * FROM tasks WHERE status = 'RUNNING' AND lease_until < UTC_TIMESTAMP(3) LIMIT 100
            """)
    List<TaskEntity> findExpiredLeases();

    @Select("""
            SELECT * FROM tasks WHERE status = 'CANCEL_REQUESTED' AND lease_until < UTC_TIMESTAMP(3) LIMIT 100
            """)
    List<TaskEntity> findStaleCancelRequested();
}
