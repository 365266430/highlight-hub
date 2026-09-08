package com.highlighthub.task;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@org.apache.ibatis.annotations.Mapper
public interface TaskAttemptMapper {

    @Insert("""
            INSERT INTO task_attempts (task_id, attempt, attempt_token, worker_id, status, started_at)
            VALUES (#{taskId}, #{attempt}, #{token}, #{workerId}, 'RUNNING', UTC_TIMESTAMP(3))
            """)
    void insertRunning(@Param("taskId") String taskId, @Param("attempt") int attempt,
                       @Param("token") String token, @Param("workerId") String workerId);

    @Update("""
            UPDATE task_attempts SET heartbeat_at = UTC_TIMESTAMP(3)
            WHERE task_id = #{taskId} AND attempt_token = #{token}
            """)
    void touchHeartbeat(@Param("taskId") String taskId, @Param("token") String token);

    @Update("""
            UPDATE task_attempts SET status = #{status}, error_code = #{errorCode}, error_message = #{errorMessage},
              finished_at = UTC_TIMESTAMP(3)
            WHERE task_id = #{taskId} AND attempt = #{attempt}
            """)
    void finish(@Param("taskId") String taskId, @Param("attempt") int attempt, @Param("status") String status,
                @Param("errorCode") String errorCode, @Param("errorMessage") String errorMessage);

    @Select("SELECT COUNT(*) FROM task_attempts WHERE task_id = #{taskId}")
    int countByTask(@Param("taskId") String taskId);
}
