package com.highlighthub.project;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

@Mapper
public interface ProjectRevisionMapper extends BaseMapper<ProjectRevisionEntity> {

    @Select("SELECT * FROM editing_project_revisions WHERE project_id = #{projectId} AND revision = #{revision}")
    ProjectRevisionEntity findRevision(@Param("projectId") String projectId, @Param("revision") int revision);
}
