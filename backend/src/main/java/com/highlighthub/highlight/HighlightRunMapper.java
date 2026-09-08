package com.highlighthub.highlight;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface HighlightRunMapper extends BaseMapper<HighlightRunEntity> {

    @Select("SELECT * FROM highlight_runs WHERE id = #{id}")
    HighlightRunEntity findById(@Param("id") String id);

    @Select("SELECT * FROM highlight_runs WHERE analysis_run_id = #{analysisRunId} ORDER BY created_at DESC")
    List<HighlightRunEntity> listByAnalysis(@Param("analysisRunId") String analysisRunId);
}
