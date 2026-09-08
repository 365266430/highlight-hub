package com.highlighthub.upload;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface UploadSessionMapper extends BaseMapper<UploadSessionEntity> {

    @Select("SELECT * FROM upload_sessions WHERE id = #{id}")
    UploadSessionEntity findById(@Param("id") String id);

    /** transition into COMPLETING; only one concurrent complete() call wins */
    @Update("""
            UPDATE upload_sessions SET status = 'COMPLETING', updated_at = UTC_TIMESTAMP(3)
            WHERE id = #{id} AND status IN ('CREATED', 'UPLOADING')
            """)
    int tryStartCompleting(@Param("id") String id);

    /** recovery for a crashed merge */
    @Update("""
            UPDATE upload_sessions SET status = 'UPLOADING', updated_at = UTC_TIMESTAMP(3)
            WHERE status = 'COMPLETING' AND updated_at < UTC_TIMESTAMP(3) - INTERVAL #{staleMinutes} MINUTE
            """)
    int resetStaleCompleting(@Param("staleMinutes") int staleMinutes);

    @Select("SELECT id FROM upload_sessions WHERE status = 'COMPLETING' " +
            "AND updated_at < UTC_TIMESTAMP(3) - INTERVAL #{staleMinutes} MINUTE LIMIT 100")
    List<String> findStaleCompletingIds(@Param("staleMinutes") int staleMinutes);

    @Update("""
            UPDATE upload_sessions SET received_bytes = (
                SELECT COALESCE(SUM(actual_size), 0) FROM upload_chunks WHERE upload_id = #{id}
            ), updated_at = UTC_TIMESTAMP(3)
            WHERE id = #{id}
            """)
    int updateReceivedBytes(@Param("id") String id);

    @Update("""
            UPDATE upload_sessions SET status = #{status}, media_id = #{mediaId}, updated_at = UTC_TIMESTAMP(3)
            WHERE id = #{id}
            """)
    int finishComplete(@Param("id") String id, @Param("status") String status, @Param("mediaId") String mediaId);
}
