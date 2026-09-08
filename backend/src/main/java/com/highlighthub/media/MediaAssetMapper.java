package com.highlighthub.media;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MediaAssetMapper extends BaseMapper<MediaAssetEntity> {

    @Select("SELECT * FROM media_assets WHERE media_id = #{mediaId} AND type = #{type} " +
            "AND status = 'ACTIVE' ORDER BY created_at DESC LIMIT 1")
    MediaAssetEntity findLatestByMediaAndType(@Param("mediaId") String mediaId, @Param("type") String type);

    @Select("SELECT * FROM media_assets WHERE id = #{id}")
    MediaAssetEntity findById(@Param("id") String id);
}
