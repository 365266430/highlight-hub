package com.highlighthub.game;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface UserGameMapper extends BaseMapper<UserGameEntity> {

    @Select("SELECT * FROM user_games WHERE user_id = #{userId} ORDER BY created_at DESC")
    List<UserGameEntity> listByUser(@Param("userId") Long userId);

    @Select("SELECT * FROM user_games WHERE id = #{id}")
    UserGameEntity findById(@Param("id") String id);
}
