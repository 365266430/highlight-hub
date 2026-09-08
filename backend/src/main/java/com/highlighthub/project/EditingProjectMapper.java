package com.highlighthub.project;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface EditingProjectMapper extends BaseMapper<EditingProjectEntity> {

    @Select("SELECT * FROM editing_projects WHERE id = #{id}")
    EditingProjectEntity findById(@Param("id") String id);

    @Select("SELECT * FROM editing_projects WHERE id = #{id} AND owner_id = #{ownerId} AND status = 'ACTIVE'")
    EditingProjectEntity findOwned(@Param("id") String id, @Param("ownerId") Long ownerId);

    @Select("""
            <script>
            SELECT * FROM editing_projects WHERE owner_id = #{ownerId} AND status = 'ACTIVE'
            ORDER BY updated_at DESC LIMIT #{size} OFFSET #{offset}
            </script>
            """)
    java.util.List<EditingProjectEntity> listOwned(@Param("ownerId") Long ownerId,
                                                   @Param("offset") long offset, @Param("size") int size);

    @Select("SELECT COUNT(*) FROM editing_projects WHERE owner_id = #{ownerId} AND status = 'ACTIVE'")
    long countOwned(@Param("ownerId") Long ownerId);

    /** optimistic revision bump: conflicts return 0 and map to HTTP 409 */
    @Update("""
            UPDATE editing_projects SET latest_revision = latest_revision + 1, name = #{name},
              updated_at = UTC_TIMESTAMP(3)
            WHERE id = #{id} AND latest_revision = #{expectedRevision} AND status = 'ACTIVE'
            """)
    int bumpRevision(@Param("id") String id, @Param("expectedRevision") int expectedRevision,
                     @Param("name") String name);
}
