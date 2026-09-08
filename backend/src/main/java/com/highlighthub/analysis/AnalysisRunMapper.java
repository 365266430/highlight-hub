package com.highlighthub.analysis;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AnalysisRunMapper extends BaseMapper<AnalysisRunEntity> {

    @Select("SELECT * FROM analysis_runs WHERE id = #{id}")
    AnalysisRunEntity findById(@Param("id") String id);
}
