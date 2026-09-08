package com.highlighthub.user;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@org.apache.ibatis.annotations.Mapper
public interface UserMapper extends BaseMapper<UserEntity> {

    @Select("SELECT * FROM users WHERE username = #{username}")
    UserEntity findByUsername(@Param("username") String username);

    @Select("SELECT COUNT(*) FROM users WHERE role = 'ADMIN'")
    Integer countAdmins();

    @org.apache.ibatis.annotations.Update("UPDATE users SET storage_quota_bytes = #{quota}, updated_at = UTC_TIMESTAMP(3) " +
            "WHERE id = #{userId}")
    int updateQuota(@Param("userId") Long userId, @Param("quota") long quota);

    /**
     * Conditional quota reservation: only succeeds when the remaining quota covers the bytes.
     * Concurrency-safe (single-statement compare-and-set).
     */
    @Update("UPDATE users SET used_bytes = used_bytes + #{bytes}, updated_at = UTC_TIMESTAMP(3) " +
            "WHERE id = #{userId} AND used_bytes + #{bytes} <= storage_quota_bytes")
    int reserveQuota(@Param("userId") Long userId, @Param("bytes") long bytes);

    @Update("UPDATE users SET used_bytes = GREATEST(used_bytes - #{bytes}, 0), updated_at = UTC_TIMESTAMP(3) " +
            "WHERE id = #{userId}")
    int releaseQuota(@Param("userId") Long userId, @Param("bytes") long bytes);
}
