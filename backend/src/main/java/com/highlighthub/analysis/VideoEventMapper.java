package com.highlighthub.analysis;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface VideoEventMapper extends BaseMapper<VideoEventEntity> {

    @Select("SELECT * FROM video_events WHERE id = #{id}")
    VideoEventEntity findById(@Param("id") String id);

    @Select("SELECT * FROM video_events WHERE media_id = #{mediaId} AND owner_id = #{ownerId} " +
            "AND status = 'ACTIVE' ORDER BY start_ms")
    List<VideoEventEntity> listActiveByMedia(@Param("mediaId") String mediaId, @Param("ownerId") Long ownerId);

    @Insert("""
            INSERT INTO event_revisions (event_id, revision, changed_by, change_json, note, created_at)
            VALUES (#{eventId}, #{revision}, #{changedBy}, #{changeJson}, #{note}, UTC_TIMESTAMP(3))
            """)
    void insertRevision(@Param("eventId") String eventId, @Param("revision") int revision,
                        @Param("changedBy") Long changedBy, @Param("changeJson") String changeJson,
                        @Param("note") String note);

    @Select("SELECT COALESCE(MAX(revision), 0) FROM event_revisions WHERE event_id = #{eventId}")
    int currentRevision(@Param("eventId") String eventId);
}
