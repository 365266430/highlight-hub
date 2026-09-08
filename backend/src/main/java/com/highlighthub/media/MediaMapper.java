package com.highlighthub.media;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface MediaMapper extends BaseMapper<MediaEntity> {

    @Select("SELECT * FROM media WHERE id = #{id}")
    MediaEntity findById(@Param("id") String id);

    @Select("SELECT * FROM media WHERE owner_id = #{ownerId} AND content_hash = #{contentHash} " +
            "AND status IN ('PROBING', 'READY') LIMIT 1")
    MediaEntity findActiveByOwnerAndHash(@Param("ownerId") Long ownerId, @Param("contentHash") String contentHash);

    @Update("""
            UPDATE media SET status = #{status}, updated_at = UTC_TIMESTAMP(3)
            WHERE id = #{id} AND status = #{expectedStatus}
            """)
    int transition(@Param("id") String id, @Param("expectedStatus") String expectedStatus,
                   @Param("status") String status);

    @Select("SELECT id FROM media WHERE status = 'PROBING' AND created_at < UTC_TIMESTAMP(3) - INTERVAL #{minutes} MINUTE LIMIT 100")
    List<String> findStuckProbing(@Param("minutes") int minutes);
}
