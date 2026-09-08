package com.highlighthub.common;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface IdempotencyRecordMapper extends BaseMapper<IdempotencyRecordEntity> {

    @Insert("""
            INSERT INTO idempotency_records (user_id, operation, idem_key, request_hash, status, created_at, expires_at)
            VALUES (#{userId}, #{operation}, #{idemKey}, #{requestHash}, 'PROCESSING', UTC_TIMESTAMP(3),
                    UTC_TIMESTAMP(3) + INTERVAL 24 HOUR)
            """)
    int insertClaim(@Param("userId") long userId, @Param("operation") String operation,
                    @Param("idemKey") String idemKey, @Param("requestHash") String requestHash);

    @Select("SELECT * FROM idempotency_records WHERE user_id = #{userId} AND operation = #{operation} AND idem_key = #{idemKey}")
    IdempotencyRecordEntity find(@Param("userId") long userId, @Param("operation") String operation,
                                 @Param("idemKey") String idemKey);

    @Update("""
            UPDATE idempotency_records SET status = 'COMPLETED', resource_type = #{resourceType},
              resource_id = #{resourceId}, response_json = #{responseJson}
            WHERE user_id = #{userId} AND operation = #{operation} AND idem_key = #{idemKey}
            """)
    int complete(@Param("userId") long userId, @Param("operation") String operation,
                 @Param("idemKey") String idemKey, @Param("resourceType") String resourceType,
                 @Param("resourceId") String resourceId, @Param("responseJson") String responseJson);

    @Update("DELETE FROM idempotency_records WHERE user_id = #{userId} AND operation = #{operation} AND idem_key = #{idemKey}")
    int deleteClaim(@Param("userId") long userId, @Param("operation") String operation,
                    @Param("idemKey") String idemKey);
}
