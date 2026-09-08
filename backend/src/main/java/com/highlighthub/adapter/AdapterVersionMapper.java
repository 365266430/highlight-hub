package com.highlighthub.adapter;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AdapterVersionMapper extends BaseMapper<AdapterVersionEntity> {

    @Select("SELECT * FROM adapter_versions WHERE adapter_id = #{adapterId} ORDER BY adapter_version DESC")
    List<AdapterVersionEntity> listByAdapter(@Param("adapterId") String adapterId);

    @Select("SELECT * FROM adapter_versions WHERE id = #{id}")
    AdapterVersionEntity findById(@Param("id") String id);
}
