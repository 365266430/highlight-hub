package com.highlighthub.adapter;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

public class AdapterEntities {
    @TableName("adapter_definitions")
    public static class Definition {
        @TableId(type = IdType.ASSIGN_UUID)
        public String id;
        public String gameKey;
        public String displayName;
        public LocalDateTime createdAt;
        public LocalDateTime updatedAt;
    }

    @TableName("adapter_versions")
    public static class Version {
        @TableId(type = IdType.ASSIGN_UUID)
        public String id;
        public String adapterId;
        public Integer adapterVersion;
        public String templateVersion;
        public String status;
        public String supportedLayouts;
        public String supportedResolutions;
        public String supportedLanguages;
        public String supportedEventTypes;
        public String configJson;
        public String notes;
        public LocalDateTime createdAt;
    }
}

@Mapper
interface AdapterDefinitionMapper extends BaseMapper<AdapterEntities.Definition> {
    @Select("SELECT * FROM adapter_definitions ORDER BY display_name")
    List<AdapterEntities.Definition> listAll();
}

@Mapper
interface AdapterVersionMapper extends BaseMapper<AdapterEntities.Version> {
    @Select("SELECT * FROM adapter_versions WHERE adapter_id = #{adapterId} ORDER BY adapter_version DESC")
    List<AdapterEntities.Version> listByAdapter(@Param("adapterId") String adapterId);
}
