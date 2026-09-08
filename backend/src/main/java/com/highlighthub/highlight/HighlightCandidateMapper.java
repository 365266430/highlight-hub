package com.highlighthub.highlight;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface HighlightCandidateMapper extends BaseMapper<HighlightCandidateEntity> {

    @Select("SELECT * FROM highlight_candidates WHERE id = #{id}")
    HighlightCandidateEntity findById(@Param("id") String id);

    @Select("SELECT * FROM highlight_candidates WHERE highlight_run_id = #{runId} ORDER BY start_ms")
    List<HighlightCandidateEntity> listByRun(@Param("runId") String runId);
}
