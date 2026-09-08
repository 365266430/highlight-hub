package com.highlighthub.render;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RenderJobMapper extends BaseMapper<RenderJobEntity> {

    @Select("SELECT * FROM render_jobs WHERE id = #{id}")
    RenderJobEntity findById(@Param("id") String id);

    @Select("SELECT * FROM render_jobs WHERE task_id = #{taskId}")
    RenderJobEntity findByTaskId(@Param("taskId") String taskId);
}
