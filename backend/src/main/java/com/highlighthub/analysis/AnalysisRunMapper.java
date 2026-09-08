package com.highlighthub.analysis;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AnalysisRunMapper extends BaseMapper<AnalysisRunEntity> {

    @Select("SELECT * FROM analysis_runs WHERE id = #{id}")
    AnalysisRunEntity findById(@Param("id") String id);

    /** result reuse key: content-hash is implied by mediaId+adapterVersion; params+algorithm decide identity */
    @Select("SELECT * FROM analysis_runs WHERE media_id = #{mediaId} AND adapter_version_id = #{adapterVersionId} " +
            "AND params_hash = #{paramsHash} AND algorithm_version = #{algorithmVersion} " +
            "AND status = 'SUCCEEDED' ORDER BY created_at DESC LIMIT 1")
    AnalysisRunEntity findReusable(@Param("mediaId") String mediaId,
                                   @Param("adapterVersionId") String adapterVersionId,
                                   @Param("paramsHash") String paramsHash,
                                   @Param("algorithmVersion") String algorithmVersion);
}
