package com.highlighthub.adapter;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AdapterDefinitionMapper extends BaseMapper<AdapterDefinitionEntity> {

    @Select("SELECT * FROM adapter_definitions ORDER BY display_name")
    List<AdapterDefinitionEntity> listAll();

    @Select("SELECT * FROM adapter_definitions WHERE game_key = #{gameKey}")
    AdapterDefinitionEntity findByGameKey(@Param("gameKey") String gameKey);
}
