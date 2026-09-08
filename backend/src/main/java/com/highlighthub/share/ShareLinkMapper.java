package com.highlighthub.share;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ShareLinkMapper extends BaseMapper<ShareLinkEntity> {

    @Select("SELECT * FROM share_links WHERE token_hash = #{tokenHash} LIMIT 1")
    ShareLinkEntity findByTokenHash(@Param("tokenHash") String tokenHash);
}
