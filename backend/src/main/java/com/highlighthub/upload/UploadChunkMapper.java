package com.highlighthub.upload;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface UploadChunkMapper extends BaseMapper<UploadChunkEntity> {

    @Select("SELECT * FROM upload_chunks WHERE upload_id = #{uploadId} AND chunk_index = #{chunkIndex}")
    UploadChunkEntity findChunk(@Param("uploadId") String uploadId, @Param("chunkIndex") int chunkIndex);

    @Select("SELECT chunk_index FROM upload_chunks WHERE upload_id = #{uploadId} ORDER BY chunk_index")
    List<Integer> receivedIndexes(@Param("uploadId") String uploadId);

    @Select("SELECT * FROM upload_chunks WHERE upload_id = #{uploadId} ORDER BY chunk_index")
    List<UploadChunkEntity> listChunks(@Param("uploadId") String uploadId);

    @Select("SELECT COALESCE(SUM(actual_size), 0) FROM upload_chunks WHERE upload_id = #{uploadId}")
    long totalReceivedBytes(@Param("uploadId") String uploadId);
}
