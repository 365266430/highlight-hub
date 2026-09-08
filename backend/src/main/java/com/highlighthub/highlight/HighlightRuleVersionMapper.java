package com.highlighthub.highlight;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface HighlightRuleVersionMapper extends BaseMapper<HighlightRuleVersionEntity> {

    @Select("SELECT * FROM highlight_rule_versions WHERE params_hash = #{hash} LIMIT 1")
    HighlightRuleVersionEntity findByParamsHash(@Param("hash") String hash);

    @Select("SELECT COALESCE(MAX(version), 0) + 1 FROM highlight_rule_versions WHERE name = #{name}")
    int nextVersion(@Param("name") String name);
}
